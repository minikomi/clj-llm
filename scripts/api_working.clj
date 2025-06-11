#!/usr/bin/env bb
(import '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
        '[java.net URI]
        '[java.time Duration])

(defn your-api-key []
  (System/getenv "OPENAI_API_KEY"))

(import '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
        '[java.net URI]
        '[java.time Duration])

(defn java-http-stream [messages]
  (println "Starting at:" (System/currentTimeMillis))

  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                   (.uri (URI/create "https://api.openai.com/v1/chat/completions"))
                   (.header "Authorization" (str "Bearer " your-api-key))
                   (.header "Content-Type" "application/json")
                   (.POST (HttpRequest$BodyPublishers/ofString
                          (json/generate-string
                            {:model "gpt-4"
                             :messages messages
                             :stream true})))
                   (.timeout (Duration/ofSeconds 60))
                   (.build))]

    (println "Sending request...")
    (let [response (.send client request (HttpResponse$BodyHandlers/ofInputStream))]
      (println "Response at:" (System/currentTimeMillis) "Status:" (.statusCode response))
      (println "Headers:" (into {} (.headers response)))

      (with-open [reader (java.io.BufferedReader.
                         (java.io.InputStreamReader. (.body response)))]
        (println "Starting to read stream...")
        (loop [line-count 0]
          (let [line (.readLine reader)]
            (if line
              (do
                (when (= line-count 0)
                  (println "First line at:" (System/currentTimeMillis)))

                (println "Line" line-count ":" line)

                (when (.startsWith line "data: ")
                  (let [data-part (.substring line 6)]
                    (when-not (= data-part "[DONE]")
                      (try
                        (let [parsed (json/parse-string data-part true)
                              content (get-in parsed [:choices 0 :delta :content])]
                          (when content
                            (print content)
                            (flush)))
                        (catch Exception e
                          (println "Parse error:" (.getMessage e)))))))

                (recur (inc line-count)))

              (println "Stream ended at:" (System/currentTimeMillis) "Total lines:" line-count))))))))

;; Test with something that should generate multiple chunks
(java-http-stream [{:role "user" :content "Count from 1 to 10 with explanations"}])
