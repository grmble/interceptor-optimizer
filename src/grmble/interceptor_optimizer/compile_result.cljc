(ns grmble.interceptor-optimizer.compile-result
  (:require
   [grmble.interceptor-optimizer.core :as ic]
   [reitit.interceptor :refer [chain map->Endpoint]]))

;;
;; this is reitit.interceptor/compile-result
;;
(defn optimized-compile-result
  ([route opts]
   (optimized-compile-result route opts nil))
  ([[_ {:keys [interceptors handler] :as data}] {::keys [queue] :as opts} _]
   (let [chain (chain (into (vec interceptors) [handler]) data opts)]
     (map->Endpoint
      {:interceptors (ic/optimize chain)
       :queue ((or queue identity) chain)
       :data data}))))
