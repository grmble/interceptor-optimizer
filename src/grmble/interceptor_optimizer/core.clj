(ns grmble.interceptor-optimizer.core
  (:import
   [reitit.interceptor Interceptor])
  (:require
   [grmble.interceptor-optimizer.into-interceptor :as ii]
   [reitit.interceptor.sieppari :as s]
   [reitit.interceptor :as ri]
   [reitit.http :as rh]
   [reitit.http.interceptors.exception :as re]))

(defn- specialize-interceptor [^Interceptor i kw kw-composable?]
  (cond-> i
    (not (= kw :enter)) (assoc :enter nil)
    (not (= kw :leave)) (assoc :leave nil)
    (not (= kw :error)) (assoc :error nil)
    true (assoc :composable (kw-composable? kw))))

(defn- composable-set [^Interceptor i]
  (cond-> ()
    (:composable (meta (:enter i))) (conj :enter)
    (:composable (meta (:leave i))) (conj :leave)
    true set))

;; enter, then leave, then errors - errors and leaves will be reversed,
;; and we want the errors before leaves
(defn- split-enter-leave-error [^Interceptor i]
  (let [cset (composable-set i)
        c?   (seq cset)]
    (if c?
      (into [] (comp
                (filter (fn [kw] (kw i))) ; only kws with associated values
                (map (fn [kw] (specialize-interceptor i kw cset))))
            [:enter :leave :error])
      [i])))

(defn find-runs
  "Finds alternating runs of composable and non-composable interceptors.
   
   At this stage, these are 'specialized' interceptors: a separate entry
   for every interceptor function (`:enter`, `:leave`, `:error`).
   They are annotated with their original position and what is
   composable (`:enter` or `:run`).

   The composability of the first is the determined by the first interceptor.
   "

  [interceptors]
  (let [interceptors (into [] (comp
                               (map-indexed #(assoc %2 :position %))
                               (mapcat split-enter-leave-error))
                           interceptors)
        enter (filter :enter interceptors)
        other (remove :enter interceptors)
        interceptors (concat enter (reverse other))]
    (partition-by #(boolean (:composable %)) interceptors)))

(defn- composable-function [{:keys [composable] :as specint}]
  (composable specint))

(defn compose-run
  "Composes the sequence for a run of consecutive composables or non-composables."
  [run-seq]
  (let [elem (first run-seq)]
    (if (:composable elem)
      (let [composed (->> run-seq
                          (map composable-function)
                          (reverse)
                          (apply comp))]
        [(assoc elem (:composable elem) composed)])
      run-seq)))

(defn optimize [interceptors]
  (println "optimize called")
  (->> interceptors
       (find-runs)
       (map compose-run)
       (apply concat)
       (sort-by :position)
       (map #(dissoc % :position :composable))))

(comment

  ;; have to change compile-result as well, 
  ;; otherwise we have our optimized interceptors but
  ;; it wants us to combine it with a handler

  (defn optimized-compile-result
    ([route opts] (optimized-compile-result route opts nil))
    ([[_ {:keys [interceptors handler] :as data} :as route] opts arg3]
     (let [interceptors (optimize (-> (into [] interceptors)
                                      (conj handler)))]
       (ri/compile-result (assoc-in route []
                                   ;; this breaks up our optimized thing again
                                    :interceptors interceptors
                                    :handler identity)
                          opts arg3)))))

(comment
  ;; test interceptors: exception handler and a counter
  (def interceptors
    [(re/exception-interceptor)
     (ii/into-interceptor {:name ::counter
                           :enter ^:composable (fn [ctx]
                                                 ; (throw (ex-info "blubb" {}))
                                                 (update-in ctx [:request :counter]
                                                            #(inc (or % 0))))
                           :leave ^:composable (fn [ctx]
                                                 (update-in ctx [:request :counter]
                                                            dec))}
                          nil nil)])

  (def handler (ii/into-interceptor
                ^:composable (fn [ctx] (str "The counter is "
                                            (get-in ctx [:counter])
                                            ", but the answer is "
                                            42)) nil nil))

  (def app (rh/ring-handler
            (rh/router ["/answer" {:interceptors interceptors
                                   :handler handler}])
            {:executor s/executor}))

  (time (app {:request-method :get :uri "/answer"}))

  (find-runs (conj interceptors handler))

  (compose-run (first (find-runs (conj interceptors handler))))

  (optimize (conj interceptors handler))


  (def oapp (rh/ring-handler
             (rh/router ["/answer" {:interceptors interceptors
                                    :handler handler}]
                        {:compile (comp ri/compile-result optimize)})
             {:compile (comp ri/compile-result optimize)
              :executor s/executor}))

  (time (oapp {:request-method :get :uri "/answer"})))
