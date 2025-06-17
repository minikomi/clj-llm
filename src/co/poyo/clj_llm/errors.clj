(ns co.poyo.clj-llm.errors
  "Comprehensive error handling for clj-llm.
   
   Defines error categories and provides consistent error creation/handling utilities.
   
   Error categories:
   - Network errors: Connection issues, timeouts
   - Provider errors: API-specific errors, rate limits, authentication
   - Validation errors: Invalid requests, schema failures
   - Internal errors: Parsing failures, unexpected errors"
  (:require [clojure.string :as str]))

;; ──────────────────────────────────────────────────────────────
;; Error categories
;; ──────────────────────────────────────────────────────────────

(def error-categories
  "Map of error types to their categories for better error handling strategies."
  {:llm/network-error :network
   :llm/timeout :network
   :llm/connection-refused :network

   :llm/rate-limit :provider
   :llm/invalid-api-key :provider
   :llm/quota-exceeded :provider
   :llm/model-not-found :provider
   :llm/invalid-model :provider

   :llm/invalid-request :validation
   :llm/schema-validation :validation
   :llm/invalid-options :validation

   :llm/unexpected-error :internal
   :llm/parse-error :internal
   :llm/stream-error :internal})

(defn error-category
  "Get the category for an error type."
  [error-type]
  (get error-categories error-type :unknown))

;; ──────────────────────────────────────────────────────────────
;; Error creation
;; ──────────────────────────────────────────────────────────────

(defn llm-error
  "Create a consistent LLM error with proper context.
   
   Args:
     error-type - Keyword from error-categories (e.g. :llm/network-error)
     message    - Human-readable error message
     context    - Map of additional context (request, response, etc.)
     cause      - Optional underlying exception
     
   Returns:
     ExceptionInfo with consistent structure"
  ([error-type message context]
   (llm-error error-type message context nil))
  ([error-type message context cause]
   (ex-info message
            (merge {:type error-type
                    :category (error-category error-type)
                    :timestamp (System/currentTimeMillis)}
                   context)
            cause)))

;; ──────────────────────────────────────────────────────────────
;; Network errors
;; ──────────────────────────────────────────────────────────────

(defn network-error
  "Create a network error with request context."
  [message request & {:keys [cause]}]
  (llm-error :llm/network-error
             message
             {:request request}
             cause))

(defn timeout-error
  "Create a timeout error."
  [message timeout-ms request]
  (llm-error :llm/timeout
             message
             {:timeout-ms timeout-ms
              :request request}))

(defn connection-refused
  "Create a connection refused error."
  [url cause]
  (llm-error :llm/connection-refused
             (str "Connection refused: " url)
             {:url url}
             cause))

;; ──────────────────────────────────────────────────────────────
;; Provider errors
;; ──────────────────────────────────────────────────────────────

(defn rate-limit-error
  "Create a rate limit error with retry information."
  [provider & {:keys [retry-after reset-time remaining]}]
  (llm-error :llm/rate-limit
             (str "Rate limit exceeded for " provider)
             (cond-> {:provider provider}
               retry-after (assoc :retry-after retry-after)
               reset-time (assoc :reset-time reset-time)
               remaining (assoc :remaining remaining))))

(defn invalid-api-key
  "Create an invalid API key error."
  [provider]
  (llm-error :llm/invalid-api-key
             (str "Invalid or missing API key for " provider)
             {:provider provider
              :hint "Check your API key configuration"}))

(defn quota-exceeded
  "Create a quota exceeded error."
  [provider & {:keys [quota-type limit]}]
  (llm-error :llm/quota-exceeded
             (str "Quota exceeded for " provider)
             (cond-> {:provider provider}
               quota-type (assoc :quota-type quota-type)
               limit (assoc :limit limit))))

(defn model-not-found
  "Create a model not found error."
  [provider model available-models]
  (llm-error :llm/model-not-found
             (str "Model '" model "' not found for " provider)
             {:provider provider
              :model model
              :available-models available-models
              :hint (str "Try one of: " (str/join ", " available-models))}))

;; ──────────────────────────────────────────────────────────────
;; Validation errors
;; ──────────────────────────────────────────────────────────────

(defn invalid-request
  "Create an invalid request error."
  [message validation-errors request]
  (llm-error :llm/invalid-request
             message
             {:validation-errors validation-errors
              :request request}))

(defn schema-validation-error
  "Create a schema validation error."
  [schema value errors]
  (llm-error :llm/schema-validation
             "Schema validation failed"
             {:schema schema
              :value value
              :errors errors}))

(defn invalid-options
  "Create an invalid options error."
  [invalid-keys valid-keys opts]
  (llm-error :llm/invalid-options
             (str "Invalid options: " (str/join ", " invalid-keys))
             {:invalid-keys invalid-keys
              :valid-keys valid-keys
              :provided-options opts
              :hint (str "Valid options are: " (str/join ", " valid-keys))}))

;; ──────────────────────────────────────────────────────────────
;; Internal errors
;; ──────────────────────────────────────────────────────────────

(defn unexpected-error
  "Create an unexpected error."
  [message context cause]
  (llm-error :llm/unexpected-error
             message
             context
             cause))

(defn parse-error
  "Create a parse error."
  [message & {:keys [input position cause]}]
  (llm-error :llm/parse-error
             message
             (cond-> {}
               input (assoc :input input)
               position (assoc :position position))
             cause))

(defn stream-error
  "Create a streaming error."
  [message & {:keys [event cause]}]
  (llm-error :llm/stream-error
             message
             (cond-> {}
               event (assoc :event event))
             cause))

;; ──────────────────────────────────────────────────────────────
;; Error handling utilities
;; ──────────────────────────────────────────────────────────────

(defn error?
  "Check if a value is an LLM error."
  [x]
  (and (instance? clojure.lang.ExceptionInfo x)
       (contains? error-categories (:type (ex-data x)))))

(defn error-type
  "Get the error type from an exception."
  [ex]
  (when (instance? clojure.lang.ExceptionInfo ex)
    (:type (ex-data ex))))

(defn retryable?
  "Check if an error is retryable based on its type."
  [ex]
  (contains? #{:llm/network-error
               :llm/timeout
               :llm/rate-limit
               :llm/connection-refused}
             (error-type ex)))

(defn extract-retry-after
  "Extract retry-after information from an error."
  [ex]
  (when (instance? clojure.lang.ExceptionInfo ex)
    (let [data (ex-data ex)]
      (or (:retry-after data)
          (when-let [reset (:reset-time data)]
            (max 0 (- reset (System/currentTimeMillis))))))))

(defn format-error
  "Format an error for user display."
  [ex]
  (if (error? ex)
    (let [data (ex-data ex)
          msg (ex-message ex)]
      (str msg
           (when-let [hint (:hint data)]
             (str "\n" hint))))
    (str ex)))

;; ──────────────────────────────────────────────────────────────
;; HTTP error parsing
;; ──────────────────────────────────────────────────────────────

(defn parse-http-error
  "Parse HTTP error responses into appropriate LLM errors.
   
   Args:
     provider - Provider name (e.g. \"openai\", \"anthropic\")
     status   - HTTP status code
     body     - Response body (map or string)
     request  - Original request for context
     
   Returns:
     Appropriate LLM error based on status and body"
  [provider status body request]
  (case status
    401 (invalid-api-key provider)

    403 (if (and (map? body)
                 (or (str/includes? (str body) "quota")
                     (str/includes? (str body) "limit")))
          (quota-exceeded provider)
          (llm-error :llm/forbidden
                     (str "Access forbidden for " provider)
                     {:provider provider
                      :response body
                      :request request}))

    404 (if (and (map? body) (:model request))
          (model-not-found provider (:model request) [])
          (llm-error :llm/not-found
                     "Resource not found"
                     {:provider provider
                      :response body
                      :request request}))

    429 (rate-limit-error provider
                          :retry-after (get-in body [:error :retry_after])
                          :reset-time (get-in body [:error :reset_time]))

    (400 402 405 406 409 410 422)
    (invalid-request (str "Invalid request to " provider ": "
                          (or (get-in body [:error :message])
                              body))
                     body
                     request)

    (500 502 503 504)
    (network-error (str "Server error from " provider ": " status)
                   request
                   :cause (when (string? body)
                            (Exception. body)))

    ;; Default
    (llm-error :llm/http-error
               (str "HTTP error " status " from " provider)
               {:provider provider
                :status status
                :response body
                :request request})))