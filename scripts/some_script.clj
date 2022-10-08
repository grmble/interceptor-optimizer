#! /usr/bin/env bb
(ns some-script
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.tools.cli :as cli]))

(defn some-fun []
  (println "this function can be called from tasks via the fully qualifed name"))

;; this is babasha version of the python idiom to distinguish
;; the file being required from the file being run as the main script.
;;
;; note that calling into the script from the task runner will count as "main script"
(when *file* (System/getProperty "babashka.file")
      (println "some-script started from babashka")
      (let [opts (cli/parse-opts *command-line-args* [[nil "--examples" "Show examples"
                                                       :default false]])]
        (when (-> opts :options :examples)
          ;; parsing command line arguments
          (-> (cli/parse-opts *command-line-args* [["-f" "--file FILE"]])
              prn)

          ;; reading a single edn value from stdin
          (-> (edn/read *in*))

          ;; lazy seq of lines
          (-> (line-seq (io/reader *in*))
              prn)

          ;; shelling out
          (shell/sh "ls"))))

