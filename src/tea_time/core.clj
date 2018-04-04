(ns tea-time.core
  "Clocks and scheduled tasks. Provides functions for getting the current time
  and running functions (Tasks) at specific times and periods. Includes a
  threadpool for task execution, controlled by (start!) and (stop!)."
  (:import [java.util.concurrent ConcurrentSkipListSet]
           [java.util.concurrent.locks LockSupport])
  (:require [clojure.stacktrace    :refer [print-stack-trace]]
            [clojure.tools.logging :refer [warn info]]))

(defprotocol Task
  (succ [task]
    "The successive task to this one.")
  (run [task]
    "Executes this task.")
  (cancel! [task]
    "Cancel this task."))

(defprotocol Deferrable
  (defer! [this delay]
    "Schedule a task for a new time measured in seconds from now")

  (defer-micros! [this delay]
    "Schedule a task for a new time measured in microseconds from now"))


;; The clock implementation ;;;;;;;;;;;;;;;;;;;;;

(defn real-unix-time-micros
  "The current unix epoch time in microseconds, taken from
  System/currentTimeMillis"
  ^long
  []
  (* (System/currentTimeMillis) 1000))

(defn real-linear-time-micros
  "A current time on a linear scale with no fixed epoch; counts in
  microseconds. Unlike unix-time, which can pause, skip, or flow backwards,
  advances mostly monotonically at (close) to physical time, one second per
  second."
  ^long
  []
  (long (/ (System/nanoTime) 1000)))

(defn micros->seconds
  "Convert microseconds to seconds, as doubles."
  ^double
  [t]
  (/ t 1000000.0))

(defn seconds->micros
  "Convert seconds to microseconds, as longs."
  ^long
  [t]
  (long (* t 1000000)))

(defn ^double real-unix-time
  "The current unix epoch time in seconds, taken from System/currentTimeMillis"
  ^double
  []
  (micros->seconds (real-unix-time-micros)))

(defn real-linear-time
  "The current linear time in seconds, taken from System/nanoTime"
  ^double
  []
  (micros->seconds (real-linear-time-micros)))

;; The clock API ;;;;;;;;;;;;;;;;;;;;;;;;;

(def unix-time-micros
  "Rebindable alias for real-unix-time-micros"
  real-unix-time-micros)

(def linear-time-micros
  "Rebindable alias for real-linear-time-micros"
  real-linear-time-micros)

(def unix-time
  "Rebindable alias for real-unix-time"
  real-unix-time)

(def linear-time
  "Rebindable alias for real-linear-time"
  real-linear-time)

;; More conversions ;;;;;;;;;;;;;;;;;;;;;

(defn unix-micros->linear-micros
  "Converts an instant in the unix timescale to an instant on the linear
  timescale, approximately."
  ^long
  [^long unix]
  (+ (linear-time-micros) (- unix (unix-time-micros))))

;; Global state ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; TODO: pull this stuff out into some sort of configurable Scheduler datatype,
; and provide a global default?

(def max-task-id
  (atom 0))

(def ^ConcurrentSkipListSet tasks
  "Scheduled operations."
  (ConcurrentSkipListSet.
    (fn [a b] (compare [(:t a) (:id a)]
                       [(:t b) (:id b)]))))

(def thread-count
  "Number of threads in the threadpool"
  4)

(def park-interval-micros
  "Time we might sleep when nothing is scheduled, in micros."
  10000)

(def threadpool
  (atom []))

(def running
  "Whether the threadpool is currently supposed to be alive."
  (atom false))

;; Scheduling guts ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ceil
  "Ceiling. For integers, identity. For other things, uses Math/ceil and
  coerces to long."
  [x]
  (if (integer? x)
    x
    (long (Math/ceil x))))

(defn task-id
  "Return a new task ID."
  []
  (swap! max-task-id inc))

(defn next-tick
  "Given a period dt, beginning at some point in time anchor, finds the next
  tick after time now, such that the next tick is separate from anchor by an
  exact multiple of dt. If now is omitted, defaults to (linear-time), and both
  anchor and dt are in seconds. If now is passed, anchor, dt, and now can be in
  any unit, so long as they all agree."
  ([anchor dt]
   (next-tick anchor dt (linear-time)))
  ([anchor dt now]
   (+ now (- dt (mod (- now anchor) dt)))))

; Look at all these bang! methods! Mutability is SO EXCITING!

(defn reset-tasks!
  "Resets the task queue to empty, without triggering side effects."
  []
  (.clear tasks))

(defn poll-task!
  "Removes the next task from the queue."
  []
  (.pollFirst tasks))

(defn schedule-sneaky!
  "Schedules a task. Does *not* awaken any threads."
  [task]
  (.add tasks task)
  task)

(defn schedule!
  "Schedule a task. May awaken a thread from the threadpool to investigate."
  [task]
  (schedule-sneaky! task)
  (when @running
    (LockSupport/unpark (rand-nth @threadpool)))
  task)

;; Task datatypes ;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Once [id f ^long t cancelled]
  Task
  (succ [this] nil)
  (run [this] (when-not @cancelled (f)))
  (cancel! [this]
          (reset! cancelled true)))

(defrecord Every [id f ^long t ^long interval deferred-t cancelled]
  Task
  (succ [this]
        (when-not @cancelled
          (let [next-time (or @deferred-t (+ t interval))]
            (reset! deferred-t nil)
            (assoc this :t next-time))))

  (run [this]
       (when-not (or @deferred-t @cancelled) (f)))

  (cancel! [this]
          (reset! cancelled true))

  Deferrable
  (defer! [this delay]
    (micros->seconds (defer-micros! this (seconds->micros delay))))

  (defer-micros! [this delay]
    (reset! deferred-t (+ (linear-time-micros) delay))))

(defn at-linear-micros!
  "Calls f at t microseconds on the linear timescale."
  [t f]
  (schedule! (Once. (task-id) f t (atom false))))

(defn at-unix-micros!
  "Calls f at t microseconds on the unix timescale. We convert this time to the
  linear timescale, so it may behave oddly across leap seconds."
  [t f]
  (at-linear-micros! (unix-micros->linear-micros t) f))

(defn at-unix!
  "Calls f at t seconds on the unix timescale. We convert this time to
  the linear timescale, so it may behave oddly across leap seconds."
  [t f]
  (at-unix-micros! (seconds->micros t) f))

(defn after!
  "Calls f after delay seconds."
  [delay f]
  (schedule! (Once. (task-id)
                    f
                    (+ (linear-time-micros) (seconds->micros delay))
                    (atom false))))

(defn every!
  "Calls f every interval seconds, after delay, also in seconds. If no delay is
  provided, starts immediately."
  ([interval f]
   (every! interval 0 f))
  ([interval delay f]
   (assert (not (neg? delay)))
   (schedule! (Every. (task-id)
                      f
                      (+ (linear-time-micros) (seconds->micros delay))
                      (seconds->micros interval)
                      (atom nil)
                      (atom false)))))

(defn run-tasks!
  "While running, takes tasks from the queue and executes them when ready. Will
  park the current thread when no tasks are available."
  [i]
  (while @running
    (try
      (if-let [task (poll-task!)]
        ; We've acquired a task.
        (do
          ; (info "Task acquired")
          (if (<= (:t task) (linear-time-micros))
            ; This task is ready to run
            (do
              ;(info :task task :time (linear-time-micros))
              ; Run task
              (try
                (run task)
                (catch Exception e
                  (warn e "Tea-Time task" task "threw"))
                (catch AssertionError t
                  (warn t "Tea-Time task" task "threw")))
              (when-let [task' (succ task)]
                ; Schedule the next task.
                (schedule-sneaky! task')))
            (do
              ; Return task.
              (schedule-sneaky! task)
              ; Park until that task comes up next. We can't use parkUntil cuz
              ; it uses posix time which is non-monotonic. WHYYYYYY Note that
              ; we're sleeping 100 microseconds minimum, and aiming to wake up
              ; 1 ms before, so we have a better chance of actually executing
              ; on time.
              (LockSupport/parkNanos
                (* 1000 (max 10 (- (:t task) (linear-time-micros) 1000)))))))

        ; No task available; park for a bit and try again.
        (LockSupport/parkNanos (* 1000 park-interval-micros)))
      (catch Exception e
      (warn e "tea-time task threw"))
    (catch AssertionError t
      (warn t "tea-time task threw")))))

(defn stop!
  "Stops the task threadpool. Waits for threads to exit. Repeated calls to stop
  are noops."
  []
  (locking threadpool
    (when @running
      (reset! running false)
      (while (some #(.isAlive ^Thread %) @threadpool)
        ; Allow at most 1/10th park-interval to pass after all threads exit.
        (Thread/sleep (/ park-interval-micros 10000)))
      (reset! threadpool []))))

(defn start!
  "Starts the threadpool to execute tasks on the queue automatically. Repeated
  calls to start are noops."
  []
  (locking threadpool
    (when-not @running
      (reset! running true)
      (reset! threadpool
              (map (fn [i]
                     (let [^Runnable f (bound-fn [] (run-tasks! i))]
                       (doto (Thread. f (str "Tea-Time " i))
                         (.start))))
                   (range thread-count))))))

(def threadpool-users
  "Number of callers who would like a threadpool open right now"
  (atom 0))

(defmacro with-threadpool
  "Ensures the threadpool is running within `body`, which is evaluated in an
  implicit `do`. Multiple threads can call with-threadpool
  concurrently. If any thread is within `with-threadpool`, the pool will run,
  and when no threads are within `with-threadpool`, the pool will shut down.

  You'll probably put this in the main entry points to your program, so the
  threadpool runs for the entire life of the program."
  [& body]
  `(try (when (= 1 (swap! threadpool-users inc))
          (start!))
        ~@body
        (finally
          (when (= 0 (swap! threadpool-users dec))
            (stop!)))))
