(ns grmble.interceptor-optimizer.dev
  (:require
   [criterium.core :as cc]
   [grmble.interceptor-optimizer.core :refer [optimize find-runs compose-run]]
   [grmble.interceptor-optimizer.into-interceptor :as ii]
   [grmble.interceptor-optimizer.compile-result :as ic]
   [reitit.interceptor.sieppari :as s]
   [reitit.core :as r]
   [reitit.interceptor :as ri]
   [reitit.http :as rh]
   [reitit.http.interceptors.exception :as re]))



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


(comment

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







  (def oapp (rh/ring-handler
             (rh/router ["/answer" {:interceptors interceptors
                                    :handler handler}]
                        {:compile ic/compile-http-result})
             {:executor s/executor}))



  (find-runs (conj interceptors handler))


  (compose-run (first (find-runs (conj interceptors handler))))


  (optimize (conj interceptors handler))


  (cc/bench (app {:request-method :get :uri "/answer"}))

  (cc/bench (oapp {:request-method :get :uri "/answer"})))
