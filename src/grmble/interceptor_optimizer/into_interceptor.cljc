(ns grmble.interceptor-optimizer.into-interceptor
  (:require [clojure.pprint :as pprint]
            [reitit.interceptor :as ri]
            [reitit.exception :as exception]
            [reitit.impl :as impl]))


(defn mark-as-composable
  "Mark an interceptor as composable.
   
   Default is to mark both :enter and :leave callbacks if present.

   Can also be called with rest args of keywords, don't use anything but
   `:enter` or `:leave`.
   "
  ([interceptor]
   (mark-as-composable interceptor :enter :leave))
  ([interceptor & what]
   (reduce (fn [acc n]
             (if-let [v (n acc)]
               (assoc acc n (with-meta v {:composable true}))
               acc))
           interceptor what)))

;;
;; this is reitits into interceptor, modified to preserve
;; metadata and produce reitit interceptors
;;
;; changes:
;; * function case: preserve metadata of original function

(defprotocol IntoInterceptor
  (into-interceptor [this data opts]))


(def ^:dynamic *max-compile-depth* 10)

(extend-protocol IntoInterceptor

  #?(:clj  clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (into-interceptor [this data {::keys [registry] :as opts}]
    (if-let [interceptor (and registry (registry this))]
      (into-interceptor interceptor data opts)
      (throw
       (ex-info
        (str
         "Interceptor " this " not found in registry.\n\n"
         (if (seq registry)
           (str
            "Available interceptors in registry:\n"
            (with-out-str
              (pprint/print-table [:id :description] (for [[k v] registry] {:id k :description v}))))
           "see [reitit.interceptor/router] on how to add interceptor to the registry.\n") "\n")
        {:id this
         :registry registry}))))

  #?(:clj  clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (into-interceptor [[f & args :as form] data opts]
    (when (and (seq args) (not (fn? f)))
      (exception/fail!
       (str "Invalid Interceptor form: " form "")
       {:form form}))
    (into-interceptor (apply f args) data opts))

  #?(:clj  clojure.lang.Fn
     :cljs function)
  (into-interceptor [this data opts]
    (let [m (meta this)]
      (into-interceptor
       {:name ::handler
        :reitit.interceptor/handler this
        :enter (with-meta (fn [ctx]
                            (assoc ctx :response (this (:request ctx))))
                 m)}
       data opts)))

  #?(:clj  clojure.lang.PersistentArrayMap
     :cljs cljs.core.PersistentArrayMap)
  (into-interceptor [this data opts]
    (into-interceptor (ri/map->Interceptor this) data opts))

  #?(:clj  clojure.lang.PersistentHashMap
     :cljs cljs.core.PersistentHashMap)
  (into-interceptor [this data opts]
    (into-interceptor (ri/map->Interceptor this) data opts))

  reitit.interceptor.Interceptor
  (into-interceptor [{:keys [compile] :as this} data opts]
    (if-not compile
      this
      (let [compiled (::compiled opts 0)
            opts (assoc opts ::compiled (inc ^long compiled))]
        (when (>= ^long compiled ^long *max-compile-depth*)
          (exception/fail!
           (str "Too deep Interceptor compilation - " compiled)
           {:this this, :data data, :opts opts}))
        (if-let [interceptor (into-interceptor (compile data opts) data opts)]
          (ri/map->Interceptor
           (merge
            (dissoc this :compile)
            (impl/strip-nils interceptor)))
          nil))))

  nil
  (into-interceptor [_ _ _]))
