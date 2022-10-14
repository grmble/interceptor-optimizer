(ns grmble.interceptor-optimizer.compile-result
  (:require
   [clojure.tools.logging :as log]
   [grmble.interceptor-optimizer.core :as ic]
   [meta-merge.core :refer [meta-merge]]
   [reitit.interceptor :refer [chain map->Endpoint]]
   [reitit.ring :as ring]))

;;
;; this is reitit.interceptor/compile-result
;;
(defn compile-interceptor-result
  ([route opts]
   (compile-interceptor-result route opts nil))
  ([[_ {:keys [interceptors handler] :as data}] {::keys [queue] :as opts} _]
   (let [optimized (ic/optimize (conj (vec interceptors) handler))
         chain (chain optimized data opts)]
     (map->Endpoint
      {:interceptors chain
       :queue ((or queue identity) chain)
       :data data}))))

;;
;; this is reitit.http/compile-result
;;
(defn compile-http-result [[path data] {:keys [::default-options-endpoint expand] :as opts}]
  (let [[top childs] (ring/group-keys data)
        childs (cond-> childs
                 (and (not (:options childs)) (not (:handler top)) default-options-endpoint)
                 (assoc :options (expand default-options-endpoint opts)))
        compile (fn [[path data] opts scope]
                  (compile-interceptor-result [path data] opts scope))
        ->endpoint (fn [p d m s]
                     (let [compiled (compile [p d] opts s)]
                       (-> compiled
                           (map->Endpoint)
                           (assoc :path p)
                           (assoc :method m))))
        ->methods (fn [any? data]
                    (reduce
                     (fn [acc method]
                       (cond-> acc
                         any? (assoc method (->endpoint path data method nil))))
                     (ring/map->Methods {})
                     ring/http-methods))]
    (if-not (seq childs)
      (->methods true top)
      (reduce-kv
       (fn [acc method data]
         (let [data (meta-merge top data)]
           (assoc acc method (->endpoint path data method method))))
       (->methods (:handler top) data)
       childs))))
