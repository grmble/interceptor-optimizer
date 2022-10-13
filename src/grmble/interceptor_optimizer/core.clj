(ns grmble.interceptor-optimizer.core
  (:require
   [grmble.interceptor-optimizer.into-interceptor :as ii]))

(defn dump-metas [interceptor]
  {:name  (:name interceptor)
   :enter [(type (:enter interceptor)) (meta (:enter interceptor))]
   :leave [(type (:leave interceptor)) (meta (:leave interceptor))]})

(defn into-dump [x]
  (-> x
      (ii/into-interceptor nil nil)
      (dump-metas)))


(comment


  (into-dump {:name ::blubb
              :enter ^:composable (fn [ctx]
                                    (tap> [::enter ctx])
                                    ctx)
              :leave ^:composable (fn [ctx]
                                    (tap> [::leave ctx])
                                    ctx)})
  (into-dump (fn [_] {:status 200
                      :headers {"content-type" "text/plain"}
                      :body "Blubb"})))
