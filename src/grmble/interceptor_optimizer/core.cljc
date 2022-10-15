(ns grmble.interceptor-optimizer.core
  (:require
   [clojure.tools.logging :as log]
   [reitit.interceptor]))

(defn- specialize-interceptor [^reitit.interceptor.Interceptor i kw kw-composable?]
  (cond-> i
    (not (= kw :enter)) (assoc :enter nil)
    (not (= kw :leave)) (assoc :leave nil)
    (not (= kw :error)) (assoc :error nil)
    true (assoc :composable (kw-composable? kw))))

(defn- composable-set [^reitit.interceptor.Interceptor i]
  (cond-> ()
    (:composable (meta (:enter i))) (conj :enter)
    (:composable (meta (:leave i))) (conj :leave)
    true set))

;; enter, then leave, then errors - errors and leaves will be reversed,
;; and we want the errors before leaves
(defn- split-enter-leave-error [^reitit.interceptor.Interceptor i]
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

;; functions in comp order
;; while looking for the big bottleneck i found this small one:
;; 5% faster because we know that the last function only takes 1 argument
(defn comp1 [fns]
  (->> fns
       (reverse)
       (reduce (fn [inner outer]
                 (fn [ctx]
                   (outer (inner ctx)))))))

(defn first-by
  "Find the first element in coll where f is trueish, return f applied to that element"
  [f coll]
  (some-> (first (filter f coll))
          (f)))

(defn compose-run
  "Composes the sequence for a run of consecutive composables or non-composables.
   
   Theoretically the handler would not be needed, but without it
   sieppari execution slows down."
  [run-seq]
  (let [elem (first run-seq)]
    (if (:composable elem)
      (let [;; TODO what name should be set?
            ;; currently we keep the name of the first element for
            ;; ease of debugging the optimizer, but this might be
            ;; confusing in actual use
            composed (->> run-seq
                          (map composable-function)
                          (reverse)
                          ;; (apply comp)
                          (comp1))
            elem (assoc elem (:composable elem) composed)]
        ;; must not preserve the handler information
        ;; otherwise the handler will be recompiled
        ;; loosing the interceptors that were composed around it
        [(dissoc elem :reitit.interceptor/handler)])
      run-seq)))

(defn- stats-for-run [run]
  {:composable (:composable (first run))
   :length (count run)})

(defn- log-runs [run-seq]
  (log/warn "interceptor optimizer run lengths" (map stats-for-run run-seq))
  run-seq)

(defn optimize [{:keys [interceptors handler] :as opts}]
  (let [optimized
        (->> (conj (vec interceptors) handler)
             (find-runs)
             (map compose-run)
             (log-runs)
             (apply concat)
             (sort-by :position)
             (map #(dissoc % :position :composable))
             (vec))]
    (assoc opts
           :interceptors (pop optimized)
           :handler (-> (peek optimized)
                        (dissoc :reitit.interceptor/handler)))))
