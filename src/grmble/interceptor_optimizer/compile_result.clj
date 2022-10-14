(ns grmble.interceptor-optimizer.compile-result
  (:require
   [reitit.interceptor :refer [chain map->Endpoint]]))

;;
;; this is reitit.interceptor/compile-result
;;
(defn compile-result
  ([route opts]
   (compile-result route opts nil))
  ([[_ {:keys [interceptors handler] :as data}] {::keys [queue] :as opts} _]
   (let [chain (chain (into (vec interceptors) [handler]) data opts)]
     (map->Endpoint
      {:interceptors chain
       :queue ((or queue identity) chain)
       :data data}))))
