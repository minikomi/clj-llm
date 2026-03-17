#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backend.openai :as openai]
         '[org.httpkit.server :as hk]
         '[hiccup2.core :as h]
         '[hiccup.util :refer [raw-string]]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[cheshire.core :as json])

;; ══════ Config ══════

(def port (parse-long (or (System/getenv "PORT") "8000")))
(def chat-dir ".chat-data")
(.mkdirs (io/file chat-dir))

(def ai
  (let [k (System/getenv "OPENROUTER_KEY")]
    (-> (if k
          (openai/backend {:api-key k :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "gpt-4o-mini")}))))

;; ══════ Persistence ══════

(defn chat-file [id] (io/file chat-dir (str id ".edn")))
(defn save-chat! [{:keys [id] :as chat}] (spit (chat-file id) (pr-str chat)))
(defn load-chat [id]
  (let [f (chat-file id)] (when (.exists f) (edn/read-string (slurp f)))))
(defn list-chats []
  (->> (.listFiles (io/file chat-dir))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(edn/read-string (slurp %)))
       (sort-by :updated-at) reverse))

;; Pending streams: chat-id → {:messages [...] :llm-history [...]}
(def pending (atom {}))

;; ══════ Markdown ══════

(defn esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn md->html [text]
  (-> (esc text)
      (str/replace #"(?s)```(?:\w+)?\n(.*?)```" "<pre><code>$1</code></pre>")
      (str/replace #"`([^`]+)`" "<code>$1</code>")
      (str/replace #"\*\*(.+?)\*\*" "<strong>$1</strong>")
      (str/replace #"\n\n" "</p><p>")
      (str/replace #"\n" "<br>")
      (#(str "<p>" % "</p>"))))

(defn strip-html [s]
  (-> s (str/replace #"<[^>]*>" "") (str/replace "&amp;" "&")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">")))

;; ══════ HTML ══════

(def css "
* { box-sizing:border-box; margin:0; padding:0 }
body { font-family:-apple-system,system-ui,sans-serif; background:#0a0a0a; color:#e0e0e0; height:100vh; display:flex }
a { color:#8ab4f8; text-decoration:none }
.hamburger { display:none; position:fixed; top:12px; left:12px; z-index:200; background:#1a1a1a; border:1px solid #333; color:#ccc; font-size:20px; width:40px; height:40px; border-radius:8px; cursor:pointer; line-height:1 }
.hamburger:hover { background:#252525; color:#fff }
.overlay { display:none; position:fixed; inset:0; background:rgba(0,0,0,.5); z-index:98 }
.sidebar { width:260px; background:#111; border-right:1px solid #222; display:flex; flex-direction:column; flex-shrink:0; height:100vh; z-index:99 }
.sidebar-hdr { padding:16px; border-bottom:1px solid #222; display:flex; align-items:center; justify-content:space-between }
.sidebar-hdr h2 { font-size:14px; font-weight:600; color:#888; text-transform:uppercase; letter-spacing:.05em }
.sidebar-close { display:none; background:none; border:none; color:#666; font-size:20px; cursor:pointer; padding:4px }
.sidebar-close:hover { color:#fff }
.new-chat { display:block; padding:10px; margin:8px; border-radius:8px; background:#1a1a1a; border:1px solid #333; color:#ccc; font-size:13px; text-align:center }
.new-chat:hover { background:#252525; color:#fff }
.chat-list { flex:1; overflow-y:auto; padding:8px }
.ci { display:flex; align-items:center; padding:10px 12px; border-radius:8px; margin-bottom:2px; font-size:13px; color:#ccc }
.ci:hover { background:#1a1a1a }
.ci.active { background:#1e3a5f; color:#fff }
.ci .t { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; flex:1 }
.ci .x { visibility:hidden; color:#666; font-size:16px; padding:0 4px; cursor:pointer; border:none; background:none }
.ci:hover .x { visibility:visible }
.ci .x:hover { color:#f44 }
.main { flex:1; display:flex; flex-direction:column; height:100vh; min-width:0 }
#messages { flex:1; overflow-y:auto; padding:24px }
.m { margin-bottom:20px; max-width:768px; margin-left:auto; margin-right:auto }
.m.user .b { background:#1e3a5f; border-radius:16px 16px 4px 16px; padding:12px 16px; display:inline-block; max-width:80%; float:right }
.m.assistant .b { background:#1a1a1a; border-radius:16px 16px 16px 4px; padding:12px 16px; display:inline-block; max-width:90% }
.m .r { font-size:11px; color:#666; margin-bottom:4px; text-transform:uppercase; letter-spacing:.05em }
.m.user .r { text-align:right }
.m::after { content:''; display:table; clear:both }
.b pre { white-space:pre-wrap; font-family:'SF Mono',Monaco,monospace; font-size:13px; background:#000; padding:10px; border-radius:6px; margin:6px 0; overflow-x:auto }
.b code { font-family:'SF Mono',Monaco,monospace; font-size:13px; background:#000; padding:2px 5px; border-radius:3px }
.b pre code { background:none; padding:0 }
.b p { margin:6px 0 } .b p:first-child { margin-top:0 } .b p:last-child { margin-bottom:0 }
.input-area { padding:12px 16px 16px; max-width:816px; margin:0 auto; width:100% }
.input-row { display:flex; gap:8px }
.input-row textarea { flex:1; padding:12px 16px; border-radius:12px; border:1px solid #333; background:#111; color:#e0e0e0; font-size:16px; font-family:inherit; resize:none; outline:none; min-height:48px; max-height:200px }
.input-row textarea:focus { border-color:#555 }
.input-row button { padding:12px 20px; border-radius:12px; border:none; background:#1e3a5f; color:#fff; font-size:14px; cursor:pointer; flex-shrink:0 }
.input-row button:hover { background:#2a4a6f }
.dot { display:inline-block; width:8px; height:8px; background:#8ab4f8; border-radius:50%; animation:pulse 1s infinite; vertical-align:middle; margin-left:4px }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
.empty { display:flex; align-items:center; justify-content:center; flex:1; color:#444; font-size:18px }
@media(max-width:768px){
  .hamburger { display:flex; align-items:center; justify-content:center }
  .sidebar { position:fixed; left:-280px; top:0; bottom:0; transition:left .2s ease; width:280px }
  .sidebar.open { left:0 }
  .sidebar.open ~ .overlay { display:block }
  .sidebar-close { display:block }
  #messages { padding:16px; padding-top:56px }
  .m.user .b, .m.assistant .b { max-width:95% }
  .ci .x { visibility:visible }
}
")

(defn hic [& body] (str (h/html body)))

(defn render-msg [{:keys [role content]}]
  (hic [:div.m {:class role}
        [:div.r role]
        [:div.b (raw-string content)]]))

(defn render-msgs [messages]
  (if (empty? messages)
    (hic [:div.empty "Start a conversation"])
    (apply str (map render-msg messages))))

(defn render-sidebar [chats active-id]
  (hic
    [:div.sidebar-hdr [:h2 "Chats"] [:button.sidebar-close {:onclick "closeSidebar()"} "×"]]
    [:a.new-chat {:href "/"} "+ New chat"]
    [:div.chat-list
     (for [{:keys [id title]} chats]
       [:a.ci {:href (str "/c/" id)
               :class (when (= id active-id) "active")}
        [:span.t title]
        [:button.x {:onclick (str "event.preventDefault();event.stopPropagation();"
                                  "fetch('/c/" id "',{method:'DELETE'}).then(()=>location.href='/')")} "×"]])]))

(def client-js
  (str
    "function send(){" 
    "var ta=document.getElementById('msg');var msg=ta.value.trim();"
    "if(!msg)return;ta.value='';ta.focus();"
    "var form=document.getElementById('form');var url=form.action;"
    "fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
    "body:'message='+encodeURIComponent(msg)})"
    ".then(function(r){return r.json()})"
    ".then(function(d){"
    "if(d.chatId&&location.pathname.indexOf(d.chatId)<0){"
    "history.pushState(null,'','/c/'+d.chatId);form.action='/c/'+d.chatId+'/send';}"
    "document.getElementById('messages').innerHTML=d.messagesHtml;scrollDown();"
    "var es=new EventSource('/c/'+d.chatId+'/stream');"
    "es.onmessage=function(e){document.getElementById('messages').innerHTML=e.data;scrollDown();};"
    "es.addEventListener('done',function(e){es.close();document.getElementById('sidebar').innerHTML=e.data;});"
    "es.onerror=function(){es.close();};"
    "});}"
    "function scrollDown(){var el=document.getElementById('messages');el.scrollTop=el.scrollHeight;}"
    "function toggleSidebar(){var s=document.getElementById('sidebar');s.classList.toggle('open');}"
    "function closeSidebar(){var s=document.getElementById('sidebar');s.classList.remove('open');}"))

(defn page [chat-id]
  (let [chat (when chat-id (load-chat chat-id))
        messages (or (:messages chat) [])]
    (str (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "clj-llm chat"]
        [:style (raw-string css)]]
       [:body
        [:button.hamburger {:onclick "toggleSidebar()"} "☰"]
        [:div#sidebar.sidebar (raw-string (render-sidebar (list-chats) chat-id))]
        [:div.overlay {:onclick "closeSidebar()"}]
        [:div.main
         [:div#messages (raw-string (render-msgs messages))]
         [:div.input-area
          [:form#form.input-row {:method "POST" :action (str "/c/" (or chat-id "new") "/send")}
           [:textarea#msg {:name "message" :placeholder "Type a message..." :rows 1 :autofocus true
                           :onkeydown "if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();send()}"}]
           [:button {:type "button" :onclick "send()"} "Send"]]]]
        [:script (raw-string client-js)]]]))))

;; ══════ Handler ══════

(defn parse-form [body]
  (when body
    (into {} (map (fn [p] (let [[k v] (str/split p #"=" 2)]
                            [(java.net.URLDecoder/decode (or k "") "UTF-8")
                             (java.net.URLDecoder/decode (or v "") "UTF-8")]))
                  (str/split body #"&")))))

(defn handler [{:keys [request-method uri] :as req}]
  (let [send-path (second (re-find #"^/c/([^/]+)/send$" uri))
        stream-path (second (re-find #"^/c/([^/]+)/stream$" uri))
        chat-path (second (re-find #"^/c/([0-9a-f-]+)$" uri))]
    (cond
      ;; Home
      (and (= :get request-method) (= "/" uri))
      {:status 200 :headers {"Content-Type" "text/html"} :body (page nil)}

      ;; View chat
      (and (= :get request-method) chat-path)
      (if (load-chat chat-path)
        {:status 200 :headers {"Content-Type" "text/html"} :body (page chat-path)}
        {:status 302 :headers {"Location" "/"}})

      ;; POST send: save user msg, queue LLM stream, return JSON
      (and (= :post request-method) send-path)
      (let [body (slurp (:body req))
            params (parse-form body)
            message (get params "message")
            chat-id (if (= send-path "new") (str (java.util.UUID/randomUUID)) send-path)
            chat (or (load-chat chat-id) (assoc {:id chat-id :title "New chat" :messages []
                                                  :created-at (System/currentTimeMillis)
                                                  :updated-at (System/currentTimeMillis)}
                                                :id chat-id))
            user-msg {:role "user" :content (md->html message)}
            msgs-with-user (conj (:messages chat) user-msg)
            llm-history (mapv #(update % :content strip-html) msgs-with-user)
            msgs-html (str (render-msgs msgs-with-user)
                           (hic [:div.m.assistant [:div.r "assistant"] [:div.b [:span.dot]]]))]
        ;; Save pending state for SSE endpoint
        (swap! pending assoc chat-id {:chat chat :messages msgs-with-user
                                       :llm-history llm-history :message message})
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:chatId chat-id :messagesHtml msgs-html})})

      ;; SSE stream
      (and (= :get request-method) stream-path)
      (if-let [state (get @pending stream-path)]
        (do
          (swap! pending dissoc stream-path)
          (hk/as-channel req
            {:on-open
             (fn [ch]
               (hk/send! ch {:status 200
                             :headers {"Content-Type" "text/event-stream"
                                       "Cache-Control" "no-cache"
                                       "X-Accel-Buffering" "no"}} false)
               (future
                 (try
                   (let [{:keys [chat messages llm-history message]} state
                         send-sse! (fn [evt data]
                                     (hk/send! ch
                                       (str "event: " evt "\ndata: "
                                            (str/replace data #"\n" "\ndata: ")
                                            "\n\n") false))
                         sb (StringBuilder.)]
                     ;; Stream chunks via :on-text callback
                     (llm/generate ai
                                   {:on-text (fn [chunk]
                                               (.append sb chunk)
                                               (send-sse! "message"
                                                 (str (render-msgs messages)
                                                      (hic [:div.m.assistant
                                                            [:div.r "assistant"]
                                                            [:div.b (raw-string (md->html (.toString sb))) [:span.dot]]]))))}
                                   llm-history)
                     ;; Done
                     (let [assistant-msg {:role "assistant" :content (md->html (.toString sb))}
                           all-msgs (conj messages assistant-msg)
                           title (if (= 1 (count (filter #(= "user" (:role %)) all-msgs)))
                                   (let [t (subs message 0 (min 50 (count message)))]
                                     (if (< (count message) 50) t (str t "...")))
                                   (:title chat))
                           updated (assoc chat :messages all-msgs :title title
                                              :updated-at (System/currentTimeMillis))]
                       (save-chat! updated)
                       (send-sse! "message" (render-msgs all-msgs))
                       (send-sse! "done" (render-sidebar (list-chats) stream-path))
                       (Thread/sleep 100)
                       (hk/close ch)))
                   (catch Exception e
                     (println "Error:" (.getMessage e))
                     (hk/close ch)))))}))
        {:status 404 :body "No pending stream"})

      ;; Delete
      (and (= :delete request-method) chat-path)
      (do (.delete (chat-file chat-path))
          {:status 200 :body ""})

      :else {:status 404 :body "Not found"})))

;; ══════ Start ══════

(println (str "💬 Chat on http://localhost:" port))
(hk/run-server handler {:port port})
@(promise)
