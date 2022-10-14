(ns grmble.interceptor-optimizer.into-interceptor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [grmble.interceptor-optimizer.into-interceptor :as ii]))

(defn dump-metas [interceptor]
  {:name  (:name interceptor)
   :enter [(type (:enter interceptor)) (meta (:enter interceptor))]
   :leave [(type (:leave interceptor)) (meta (:leave interceptor))]})

(defn into-dump [x]
  (-> x
      (ii/into-interceptor nil nil)
      (dump-metas)))

(defn composable? [kw interceptor]
  (-> interceptor
      kw
      meta
      :composable))

;; with defn you don't use reader meta syntax, you give a meta attr map
;; after the docstring.  but this sets the meta on var, not on the function.
(defn increase-counter
  ([ctx]
   (update-in ctx [:request :counter] #(inc (or % 0)))))

(defn decrease-counter
  [ctx]
  (update-in ctx [:request :counter] #(dec (or % 0))))

(deftest test-into-interceptor
  (testing "handler function with reader metadata"
    (is (composable? :enter (ii/into-interceptor ^:composable (fn [_] 42) nil nil))))
  (testing "enter/leave functions with reader metadata"
    (let [interceptor (ii/into-interceptor {:enter ^:composable (fn [x] x)
                                            :leave ^:composable (fn [x] x)}
                                           nil nil)]
      (is (composable? :enter interceptor))
      (is (composable? :leave interceptor))))
  ;; with defn reader metadata does not work, you have to provide a map after the docstring
  ;; but that gets attached to the var which does not really help us
  (testing "using with-meta on named functions"
    (let [interceptor (ii/into-interceptor {:enter (with-meta increase-counter {:composable true})
                                            :leave (with-meta decrease-counter {:composable true})}
                                           nil nil)]
      (is (composable? :enter interceptor))
      (is (composable? :leave interceptor))))

  (testing "using mark-as-composable on an interceptor from named functions"
    (let [interceptor (ii/mark-as-composable (ii/into-interceptor {:enter increase-counter
                                                                   :leave decrease-counter}
                                                                  nil nil))]
      (is (composable? :enter interceptor))
      (is (composable? :leave interceptor)))))

