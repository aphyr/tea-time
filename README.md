# Tea-Time

> There was a disaster hanging silently in the air around him waiting for him to
> notice it. His knees tingled.
>
> What he needed, he had been thinking, was a client. He had been thinking that
> as a matter of habit. It was what he always thought at this time of the
> morning. What he had forgotten was that he had one.
>
> He stared wildly at his watch. Nearly eleven-thirty. He shook his head to try
> and clear the silent ringing between his ears, then made a hysterical lunge
> for his hat and his great leather coat that hung behind the door.
>
> Fifteen seconds later he left the house, five hours late but moving fast.
>
>  --Douglas Adams, "The Long Dark Tea-Time of the Soul"

Many programs need to interact with clocks: reading the current time,
scheduling some operation to be done at a particular time or a few seconds from
now, or performing some housekeeping task every few seconds. They may need to
dynamically create new tasks, push them back to a later time, and cancel them
when they are no longer needed. Moreover, testing these real-time behaviors for
side effects is notoriously slow and buggy. Tea-Time is a minimal Clojure
library which provides a global, lightweight, and testable scheduler for
exactly these purposes.

Tea-Time is adapted from the scheduler in Riemann, a distributed systems
monitoring server, where it has served for several years in
moderate-performance, long-running deployments. It's not perfect, but its API
and functionality have proven useful and stable.

Consistent use of Tea-Time can make it easier to write and test programs which
interact with wall clocks. With one call, you can switch from using wall clocks
to a virtual time, which advances only when you tell it to; scheduled tasks
evaluate synchronously, appearing to execute exactly at their target times.
Callers callers will read the virtual clock rather than the system clock. This
allows you to write tests for hours of "real-time" behavior which execute deterministically, in milliseconds.

Tea-Time is not for working with dates or human times; it works purely in POSIX
time. Tea-Time is not a parser or formatter. There's no notion of intervals or
calendars. These are all admirable goals, better served by Joda Time, Juxt's
Tick.

## Installation

Via [Clojars](https://clojars.org/tea-time)

## Quick Tour

```clj
user=> (require '[tea-time.core :as tt])
nil
user=> (tt/unix-time-micros)   ; Wall clock in microseconds
1522776026066000
user=> (tt/linear-time-micros) ; Monotonic clock in microseconds
128572305580
user=> (tt/start!)             ; Start threadpool

; Say hi after 1 second
user=> (tt/after! 1 (bound-fn [] (prn :hi)))
#tea_time.core.Once
{:cancelled #<Atom@6f21520e false>
 :f #<Fn@4919b682 clojure.core/bound_fn_STAR_[fn]>
 :id 1
 :t 128599825869}

; One second later...
:hi

; Every 10 seconds (after a 2 second wait)...
user=> (def dirk (tt/every! 10 2 (bound-fn [] (prn "THAT is a thing"))))
"THAT is a thing"
"THAT is a thing"
"THAT is a thing"
...

; Defer the next execution until 30 seconds from now
user=> (tt/defer! dirk 30)
129800.514632
... Ah, a breather ...
"THAT is a thing"

; That's over, it's cancelled
user=> (tt/cancel! dirk)
true
```

## Working with Clocks

The core library is one namespace:

```clj
user=> (require '[tea-time.core :as tt])
nil
```

Internally, Tea-time uses microseconds, represented as 64-bit signed longs, for
a balance of speed, representability, and precision. There are two timescales.
The Unix timescale, which is derived from System/currentTimeMillis,
approximately tracks "wall clock time", and can flow unevenly or even
backwards.

```clj
user=> (tt/unix-time-micros)
1522772393355000
```

For convenience and where precision is not critical, we also provide times in seconds, represented as 64-bit doubles.

```clj
user=> (tt/unix-time)
1.522772450458E9
user=> (long (tt/unix-time))
1522772475
```

You can convert back and forth:

```clj
user=> (tt/seconds->micros 1.2)
1200000
user=> (tt/micros->seconds 200)
2.0E-4
```

The linear timescale is derived from System/nanoTime, and advances
monotonically. However, it is not synchronized to any thing in particular, and
can only be used within a single JVM.

```clj
user=> (tt/linear-time)
125261.653199
user=> (tt/linear-time-micros)
125266476873
```

Use the linear timescale to measure relative times, e.g. the time it takes to
perform something in a single JVM. Use the Unix timescale to schedule things
that should be roughly synchronized across multiple JVMs. Do not use any time
for safety-critical applications: the list of ways clocks can go wrong is
effectively unbounded.

```clj
user=> (let [t1 (tt/linear-time)]
         (Thread/sleep 1000)
         (- (tt/linear-time) t1))
1.0001519999932498
```

## One-time Tasks

First, start the Tea-Time threadpool. This is a global set of worker threads which will evaluate scheduled tasks.

```clj
user=> (tt/start!)
```

You can stop the threadpool later with `tt/stop!`, which will politely finish
execution of any tasks currently being evaluated, and block until all threads
have exited.

To schedule a task after n seconds, use `after!`

```clj
user=> (def task (tt/after! 2 (bound-fn [] (prn "I took two seconds"))))
#'user/task
... wait two seconds...
user=> "I took two seconds"
```

We use `bound-fn` here to retain a handle to the repl's stdout, so `prn` works.
Regular `fn` works fine in most cases, and if you use a logger like
`clojure.tools.logging`, it'll work fine with plain old `fn` too.

You can *cancel* a task: if it hasn't been executed yet, it won't be when it
comes due. Canceling an already completed task is legal, but does nothing.

```clj
user=> (def task (tt/after! 10 (bound-fn [] (prn "I took ten seconds"))))
#'user/task
user=> (tt/cancel! task)
true
; ... nothing happens ...
```

## Recurring tasks

To schedule a recurring task, which should execute every n seconds, use
`every!`. Every takes an interval in seconds, and starts immediately.

```clj
(def task (tt/every! 2 (bound-fn [] (prn :hi))))
:hi
#'user/task
user=> :hi
:hi
:hi
user=> (tt/cancel! task)
true
; ... no more :hi's
```

You can also defer the first execution by providing an initial delay. To run
every 2 seconds, starting 5 seconds from now, say `(tt/every! 2 5 (bound-fn
(prn :hi)))`.

Recurrent tasks are also *deferrable*: you can push back the execution time to
to 10 seconds *from now*.

```clj
user=> (def task (tt/every! 2 (bound-fn [] (prn :hi))))
:hi
:hi
user=> (tt/defer! task 10)
126565647078
; Ahhh, a brief respite
:hi
:hi
```

This is particularly helpful for streaming or batching systems that accrue
events over time, and if nothing transpires for a few seconds, should flush
their state. Tea-Time makes `defer!` cheap, so you can call it on every event.

## Testing with Virtual Time

Testing real-time systems is *hard*: you usually wind up with a morass of sleep
statements, barriers, and weird race conditions. Tea-Time includes a hook to
run time-based tests *deterministically*.

First, make sure the scheduler is stopped, and pull in the virtual namespace.

```clj
user=> (tt/stop!)
[]
user=> (require '[tea-time.virtual :as tv])
nil
```

Use the `with-virtual-time!` macro to evaluate code with a virtual clock and
scheduler.

```clj
user=> (tv/with-virtual-time! (tt/unix-time))
0.0
user=> (tv/with-virtual-time! (tt/unix-time))
0.0
user=> (tv/with-virtual-time! (tt/linear-time))
0.0
user=> (tv/with-virtual-time! (tt/linear-time))
0.0
```

Time is *frozen* at 0 microseconds. Let's schedule some tasks.

```clj
user=> (tv/with-virtual-time!
          (tt/after! 2500 (bound-fn []
            (prn "I'm task 1, clocks are" (tt/unix-time) (tt/linear-time)))))
          (tt/after! 1.23 (bound-fn []
            (prn "I'm task 2, clocks are" (tt/unix-time) (tt/linear-time))))))
```

Nothing will happen. Time is still frozen. Let's jump forward to one second:

```clj
user=> (tv/advance! 1)
1.0
user=> (tv/with-virtual-time! (tt/unix-time))
1.0
```

The clock is now 1, but nothing has happened. Let's jump ahead to an hour:

```clj
user=> (tv/with-virtual-time! (tv/advance! 3600))
"I'm task 2, clocks are" 1.23 1.23
"I'm task 1, clocks are" 2500.0 2500.0
3600.0
```

Note that the tasks evaluated in their scheduled order--t2 before t1--and each
task observed the correct linear and unix times. So long as code uses
tea-time's wrappers, we can test hours of "real-time" behavior in a few
milliseconds, and obtain *deterministic* execution.

To reset the virtual clock to zero and clear all tasks, use

```clj
user=> (tv/reset-time!)
nil
user=> (tv/with-virtual-time! (tt/unix-time))
0.0
```

We provide a pair of handy fixtures for writing clojure.tests using virtualized
time:

```clj
(use-fixtures :once tv/call-with-virtual-time!)
(use-fixtures :each tv/reset-time!)
```

See `tests/` for additional examples.

## License

Copyright © 2018 Kyle Kingsbury

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
