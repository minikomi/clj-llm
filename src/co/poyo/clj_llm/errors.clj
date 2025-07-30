(ns co.poyo.clj-llm.errors
  "Simple error handling for clj-llm.")

(defn error 
  "Create an LLM error with optional data"
  ([msg] (error msg nil))
  ([msg data]
   (ex-info msg (merge {:type :llm-error} data))))

(defn retryable? 
  "Check if an error is retryable based on HTTP status"
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (boolean (#{429 500 502 503} (:status (ex-data e))))))

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

;; Legacy function names for compatibility
(def invalid-api-key (partial error "Invalid API key"))
(def stream-error error)