(ns co.poyo.clj-llm.http
  "Cross-platform HTTP utility that works in both JVM Clojure and Babashka.
   Uses reader conditionals to load the appropriate dependencies and implementations."
  (:refer-clojure :exclude [get])
  #?(:clj (:require [org.httpkit.client :as http-client])
     :bb (:require [babashka.http-client :as http-client])))

(defn request
  "Platform agnostic request function that works in both JVM Clojure and Babashka.
   It uses reader conditionals to load the appropriate HTTP client."
  [opts]
  #?(:clj (let [response @(http-client/request opts)]
            response)
     :bb (http-client/request opts)))
