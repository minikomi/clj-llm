(ns co.poyo.clj-llm.http
  "Cross-platform HTTP streaming for Clojure and Babashka.
   
   Note: Babashka's http-client doesn't support true async streaming,
   so responses will be buffered before the stream is available."
  #?(:clj (:require [org.httpkit.client :as client])
     :bb (:require [babashka.http-client :as client])))

(defn post-stream 
  "POST with streaming response. Calls callback with {:status :body :error}.
   The :body will be an InputStream for successful responses.
   
   Note: In Babashka, the entire response is buffered before streaming begins."
  [url headers body callback]
  (let [opts {:url url
              :method :post
              :headers headers
              :body body
              :as :stream
              :timeout 30000}]
    #?(:clj 
       ;; http-kit needs async? true for true streaming
       (client/request (assoc opts :async? true)
         (fn [resp]
           (callback {:status (:status resp)
                      :body (:body resp)
                      :error (:error resp)})))
       :bb
       ;; babashka.http-client is synchronous - the response is buffered
       ;; but we still get an InputStream to read from
       (try
         (let [resp (client/request opts)]
           (callback {:status (:status resp)
                      :body (:body resp)
                      :error (:error resp)}))
         (catch Exception e
           (callback {:status 0 :body nil :error e}))))))