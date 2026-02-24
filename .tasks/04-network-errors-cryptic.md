# P0: Network errors (status 0) produce cryptic messages

## Problem

`net/post-stream` sets `{:status 0 :body nil :error exception}` on
connection failures (DNS, connection refused, timeout). But
`create-event-stream` in `backend_helpers.clj` only checks `:status` —
never `:error`. Users get "HTTP 0: null" instead of "Connection refused".

## Location

`src/co/poyo/clj_llm/backends/backend_helpers.clj` — `create-event-stream`
`src/co/poyo/clj_llm/net.cljc` — `post-stream`

## Fix

In `create-event-stream`'s response callback, check `:error` before
checking `:status`:

```clojure
(cond
  (:error response)
  (go
    (>! events-chan {:type :error
                    :error (.getMessage (:error response))
                    :exception (:error response)})
    (close! events-chan))

  (= 200 (:status response))
  ;; ... existing SSE path ...

  :else
  ;; ... existing error-response path ...)
```
