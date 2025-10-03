(ns co.poyo.clj-llm.errors
  "Simple error handling for clj-llm.")

(defn error
  "Create an LLM error with optional data"
  ([msg] (error msg nil))
  ([msg data]
   (ex-info msg (merge {:type :llm-error} data))))

(defn parse-http-error
  "Convert HTTP response to exception"
  [provider status body]
  (let [msg (case status
              401 "Invalid API key"
              403 "Access forbidden" 
              404 "Resource not found"
              429 "Rate limit exceeded"
              (400 422) "Invalid request"
              (500 502 503 504) "Server error"
              (str "HTTP " status))]
    (error (str provider ": " msg) 
           {:status status 
            :body body
            :retry-after (get-in body [:error :retry_after])})))