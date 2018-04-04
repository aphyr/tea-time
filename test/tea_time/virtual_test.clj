(ns tea-time.virtual-test
  (:require [tea-time.core :refer :all]
            [tea-time.virtual :refer :all]
            [clojure.test :refer :all]))

(use-fixtures :once call-with-virtual-time!)
(use-fixtures :each reset-time!)

(deftest clock-test
         (is (= (virtual-unix-time) 0.0))
         (advance! -1)
         (is (= (virtual-unix-time) 0.0))
         (advance! 4.5)
         (is (= (virtual-unix-time) 4.5))
         (reset-time!)
         (is (= (virtual-unix-time) 0.0)))

(deftest at-test
         (let [x (atom 0)
               once1 (at-unix! 1 #(swap! x inc))
               once2 (at-unix! 2 #(swap! x inc))
               once3 (at-unix! 3 #(swap! x inc))]

           (advance! 0.5)
           (is (= @x 0))

           (advance! 2)
           (is (= @x 2))

           (cancel! once3)
           (advance! 3)
           (is (= @x 2))))

(deftest every-test
         (let [x (atom 0)
               bump #(swap! x inc)
               task (every! 1 2 bump)]

           (is (= @x 0))

           (advance! 1)
           (is (= @x 0))

           (advance! 2)
           (is (= @x 1))

           (advance! 3)
           (is (= @x 2))

           (advance! 4)
           (is (= @x 3))

           ; Double-down
           (defer! task -3)
           (is (= @x 3))
           (advance! 5)
           (is (= @x 8))

           ; Into the future!
           (defer! task 4)
           (advance! 8)
           (is (= @x 8))
           (advance! 9)
           (is (= @x 9))
           (advance! 10)
           (is (= @x 10))))
