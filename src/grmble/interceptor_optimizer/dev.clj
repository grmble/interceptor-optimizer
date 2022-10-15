(ns grmble.interceptor-optimizer.dev
  (:require
   [criterium.core :as cc]
   [grmble.interceptor-optimizer.core :refer [optimize find-runs compose-run]]
   [grmble.interceptor-optimizer.into-interceptor :as ii]
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [reitit.interceptor.sieppari :as s]
   [reitit.core :as r]
   [reitit.interceptor :as ri]
   [reitit.http :as rh]
   [reitit.http.interceptors.exception :as re]
   [reitit.exception :as exception]
   [reitit.interceptor :as interceptor]))



;; test interceptors: exception handler and a counter
(def interceptors
  [(re/exception-interceptor)
   (ii/into-interceptor {:name ::counter
                         :enter ^:composable (fn [ctx]
                                               ; (log/warn "enter")
                                               (update-in ctx [:request :counter]
                                                          #(inc (or % 0))))
                         :leave ^:composable (fn [ctx]
                                               ; (log/warn "leave")
                                               (update-in ctx [:request :counter]
                                                          dec))}
                        nil nil)])

(def handler (ii/into-interceptor
              ^:composable (fn [ctx]
                             ; (log/warn "handler")
                             (str "The counter is "
                                  (get-in ctx [:counter])
                                  ", but the answer is "
                                  42)) nil nil))

(def app (rh/ring-handler
          (rh/router ["/answer" {:interceptors interceptors
                                 :handler handler}])
          {:executor s/executor}))

(def oapp (rh/ring-handler
           (rh/router ["/answer" (optimize {:interceptors interceptors
                                            :handler handler})])
           {:executor s/executor}))

(def req {:request-method :get :uri "/answer"})


(comment

  (cc/bench (app req))

  (cc/bench (oapp req))


  (def my-opts {:interceptors interceptors
                :handler handler})

  (optimize my-opts)


  ;; manual composition
  ((comp
    (:leave (second interceptors))
    (:enter handler)
    (:enter (second interceptors))) {})

  ((:enter handler) nil)

  ((:enter (second interceptors)) {})
  ((:leave (second interceptors)) {:request {:counter 1}})


  ;; manually running the optimization result
  ((:enter (second (optimize (conj interceptors handler))))
   nil)
  ((:error (first (optimize (conj interceptors handler)))) {:error (ex-info "asdf" {})})



  (find-runs (conj interceptors handler))


  (compose-run (first (find-runs (conj interceptors handler))))


  (map type (optimize (conj interceptors handler)))

  (type handler)

  (def xe (:enter (second interceptors)))
  (def xl (:leave (second interceptors)))
  (def xh (:enter handler))

  (def by-comp (comp xl xh xe))

  (def manual (fn [x]
                (xl (xh (xe x)))))

  (time (by-comp req))
  (time (manual req))

  (cc/bench (by-comp req))
  ;; mean: 1.11003 ns 

  (cc/bench (manual req))
  ;; mean: 1.05279 ns
  )


;; test interceptors: async example
(def async-interceptors
  [(re/exception-interceptor)
   (ii/into-interceptor {:name ::counter1
                         :enter ^:composable (fn [ctx]
                                               ; (log/warn "enter")
                                               (update-in ctx [:request :counter]
                                                          #(inc (or % 0))))
                         :leave ^:composable (fn [ctx]
                                               (let [ctx (update-in ctx [:request :counter]
                                                                    dec)]
                                                 (log/warn "leave" (:counter (:request ctx)) (:response ctx)))
                                               ctx)}
                        nil nil)
   (ii/into-interceptor {:name ::counter2
                         :enter ^:composable (fn [ctx]
                                               ; (log/warn "enter")
                                               (update-in ctx [:request :counter]
                                                          #(inc (or % 0))))
                         :leave ^:composable (fn [ctx]
                                               ; (log/warn "leave")
                                               (update-in ctx [:request :counter]
                                                          dec))}
                        nil nil)])

(def async-handler (ii/into-interceptor
                    (fn [ctx]
                      (p/delay 500 (str "The counter is "
                                        (get-in ctx [:counter])
                                        ", but the answer is "
                                        42))) nil nil))

(def async-app (rh/ring-handler
                (rh/router ["/answer" {:interceptors async-interceptors
                                       :handler async-handler}])
                {:executor s/executor}))

(def oasync-app (rh/ring-handler
                 (rh/router ["/answer" (optimize {:interceptors async-interceptors
                                                  :handler async-handler})])
                 {:executor s/executor}))

(comment

  (async-app req)

  (oasync-app req))
