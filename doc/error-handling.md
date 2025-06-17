# Error Handling Architecture

## Overview

clj-llm implements a comprehensive error handling system that provides:
- Categorized error types for different failure scenarios
- Contextual information for debugging
- Retryable error detection
- Proper async error propagation

## Error Categories

### Network Errors (Retryable)
- `:llm/network-error` - General network failures
- `:llm/timeout` - Request timeouts
- `:llm/connection-refused` - Unable to connect

### Provider Errors
- `:llm/rate-limit` - Rate limiting (retryable)
- `:llm/invalid-api-key` - Authentication failure
- `:llm/quota-exceeded` - Usage limits reached
- `:llm/model-not-found` - Invalid model specified

### Validation Errors
- `:llm/invalid-request` - Malformed requests
- `:llm/schema-validation` - Structured output validation failure
- `:llm/invalid-options` - Invalid configuration options

### Internal Errors
- `:llm/unexpected-error` - Unexpected failures
- `:llm/parse-error` - JSON/response parsing failures
- `:llm/stream-error` - Streaming response errors

## Usage Examples

### Basic Error Handling
```clojure
(try
  (llm/generate ai "Hello" {:model "invalid-model"})
  (catch Exception e
    (println "Error type:" (errors/error-type e))
    (println "Retryable:" (errors/retryable? e))
    (println "Message:" (errors/format-error e))))
```

### Retry Logic
```clojure
(defn with-retry [f max-attempts]
  (loop [attempt 1]
    (let [result (try
                   {:success true :value (f)}
                   (catch Exception e
                     {:success false :error e}))]
      (if (:success result)
        (:value result)
        (if (and (errors/retryable? (:error result))
                 (<= attempt max-attempts))
          (do
            (Thread/sleep (* 1000 attempt))
            (recur (inc attempt)))
          (throw (:error result)))))))
```

### Streaming Error Handling
```clojure
(let [chunks (llm/stream ai "Tell me a story")]
  (loop []
    (when-let [chunk (<!! chunks)]
      (if (map? chunk)
        ;; Handle error marker
        (println "Stream error:" (:error chunk))
        ;; Handle text chunk
        (print chunk))
      (recur))))
```

## Implementation Details

### Error Creation
All errors use `ex-info` with consistent data:
- `:type` - Error type keyword
- `:category` - Error category for handling strategies
- `:timestamp` - When the error occurred
- Additional context based on error type

### HTTP Error Mapping
HTTP status codes are mapped to appropriate error types:
- 401 → `:llm/invalid-api-key`
- 403 → `:llm/quota-exceeded` or `:llm/forbidden`
- 404 → `:llm/model-not-found` or `:llm/not-found`
- 429 → `:llm/rate-limit`
- 5xx → `:llm/network-error`

### Async Error Propagation
Errors in streaming responses are handled by:
1. Converting error events to error markers in the stream
2. Delivering errors to promises in the `prompt` function
3. Ensuring channels are properly closed on error

## Testing

The error handling system is tested with:
- Unit tests for error creation and utilities
- Integration tests for HTTP error parsing
- Example scripts demonstrating error scenarios
- Mock providers for testing error paths

## Future Enhancements

Potential improvements:
- Automatic retry middleware
- Circuit breaker pattern
- Error metrics and monitoring hooks
- Custom error handlers per provider
- Request/response logging on errors