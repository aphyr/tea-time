(ns tea-time.virtual
  "Provides controllable periodic and deferred execution. Calling (advance!
  target-time-in-seconds) moves the clock forward, triggering events that would
  have occurred, in sequence. Each task executes exactly at its target time."
  (:require [tea-time.core :refer :all]
            [clojure.tools.logging :refer [info]]))

(def clock
  "Reference to the current time, in microseconds."
  (atom 0))

(defn reset-clock!
  []
  (reset! clock 0))

(defn reset-time!
  "Resets the clock and task queue. If a function is given, calls f after
  resetting the time and task list."
  ([f] (reset-time!) (f))
  ([]
   (reset-clock!)
   (reset-tasks!)))

(defn set-time!
  "Sets the current time in seconds, without triggering callbacks."
  [t]
  (reset! clock (seconds->micros t)))

(defn virtual-unix-time-micros
  []
  @clock)

(defn virtual-linear-time-micros
  []
  @clock)

(defn virtual-unix-time
  []
  (micros->seconds @clock))

(defn virtual-linear-time
  []
  (micros->seconds @clock))

(defn advance!
  "Advances the clock to t seconds, triggering side effects. Tasks are run
  synchronously on this thread, and their exceptions will be thrown here."
  [t]
  (let [t (seconds->micros t)]
    (when (< @clock t)
      (loop []
        (when-let [task (poll-task!)]
          (if (<= (:t task) t)
            (do
              ; Consume task
              (swap! clock max (:t task))
              (run task)
              (when-let [task' (succ task)]
                (schedule-sneaky! task'))
              (recur))
            ; Return task
            (schedule-sneaky! task))))
      (micros->seconds (swap! clock max t)))))

(defmacro with-virtual-time!
  "Switches time functions to virtual counterparts, evaluates body, and
  returns. Not at all threadsafe; bindings take effect globally. This is only
  for testing."
  [& body]
  ; Please forgive me
  `(with-redefs [tea-time.core/unix-time          virtual-unix-time
                 tea-time.core/unix-time-micros   virtual-unix-time-micros
                 tea-time.core/linear-time        virtual-linear-time
                 tea-time.core/linear-time-micros virtual-linear-time-micros]
     ~@body))

(defn call-with-virtual-time!
  "Switches time functions to time.controlled counterparts, invokes f,
  then restores them. Definitely not threadsafe. Not safe by any standard,
  come to think of it. Only for testing purposes."
  [f]
  (with-virtual-time! (f)))
