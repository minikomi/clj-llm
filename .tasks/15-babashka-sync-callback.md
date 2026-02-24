# P2: Babashka net/post-stream is synchronous, breaks async contract

## Problem

In `net.cljc`, the `#?(:bb ...)` branch calls `cb` synchronously on
the calling thread. The `#?(:clj ...)` branch calls it from `a/thread`.

`create-event-stream` returns `events-chan` assuming the callback fires
async (after the channel is returned). On Babashka, the callback fires
*before* the channel is returned to the caller.

## Location

`src/co/poyo/clj_llm/net.cljc` — `post-stream`

## Fix

Wrap the Babashka branch in a `future` or `a/thread` to match the
async contract:

```clojure
:bb
(future
  (try
    (let [{:keys [status body error]} (http/post ...)]
      (cb {:status status :body body :error error}))
    (catch Exception e
      (cb {:status 0 :body nil :error e}))))
```
