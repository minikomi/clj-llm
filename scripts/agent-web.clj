#!/usr/bin/env bb

(require '[co.poyo.clj-llm.core :as llm]
         '[co.poyo.clj-llm.backends.openai :as openai]
         '[org.httpkit.server :as hk]
         '[hiccup2.core :as h]
         '[hiccup.util :refer [raw-string]]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[babashka.http-client :as http])

(def port 8001)
(def data-dir ".chat-data/agent")
(.mkdirs (io/file data-dir))

;; ══════ Provider ══════

(def ai
  (let [openrouter-key (System/getenv "OPENROUTER_KEY")]
    (-> (if openrouter-key
          (openai/backend {:api-key openrouter-key
                            :api-base "https://openrouter.ai/api/v1"})
          (openai/backend))
        (assoc :defaults {:model (or (System/getenv "LLM_MODEL") "openai/gpt-4.1-mini")}))))

;; ══════ Tools ══════

(def geocode
  [:map {:name "geocode" :description "Look up latitude and longitude for a city"}
   [:city {:description "City name"} :string]])

(def get-weather
  [:map {:name "get_weather" :description "Get current weather at a location. Call geocode first to get coordinates."}
   [:latitude {:description "Latitude"} :double]
   [:longitude {:description "Longitude"} :double]])

(def search-web
  [:map {:name "search_web" :description "Search the web for information"}
   [:query {:description "Search query"} :string]])

(def calculate
  [:map {:name "calculate" :description "Evaluate a Clojure math expression. Use prefix notation, e.g. (+ 2 2), (Math/sqrt 144), (Math/pow 2 10)"}
   [:expression {:description "Clojure expression, e.g. (+ (Math/pow 2 10) (Math/sqrt 256))"} :string]])

(def tavily-key (System/getenv "TAVILY_API_KEY"))

(defn tavily-search [query]
  (try
    (let [resp (http/post "https://api.tavily.com/search"
                 {:headers {"Content-Type" "application/json"}
                  :body (json/generate-string
                          {:query query
                           :api_key tavily-key
                           :max_results 5
                           :include_answer true})})
          data (json/parse-string (:body resp) true)]
      (str (when-let [answer (:answer data)]
             (str "Summary: " answer "\n\n"))
           "Sources:\n"
           (str/join "\n"
             (map-indexed
               (fn [i r]
                 (str (inc i) ". " (:title r) "\n"
                      "   " (:content r) "\n"
                      "   URL: " (:url r)))
               (:results data)))))
    (catch Exception e
      (str "Search error: " (.getMessage e)))))

(def agent-tools [geocode get-weather search-web calculate])

(def wmo-codes
  {0 "Clear sky" 1 "Mainly clear" 2 "Partly cloudy" 3 "Overcast"
   45 "Fog" 48 "Rime fog" 51 "Light drizzle" 53 "Drizzle" 55 "Dense drizzle"
   61 "Slight rain" 63 "Rain" 65 "Heavy rain" 71 "Slight snow" 73 "Snow"
   75 "Heavy snow" 80 "Slight showers" 81 "Showers" 82 "Violent showers"
   95 "Thunderstorm" 96 "Thunderstorm w/ hail" 99 "Thunderstorm w/ heavy hail"})

(defn fetch-geocode [city]
  (try
    (let [geo  (-> (http/get (str "https://geocoding-api.open-meteo.com/v1/search?name="
                                  (java.net.URLEncoder/encode city "UTF-8") "&count=1"))
                   :body (json/parse-string true))
          loc  (first (:results geo))]
      (if-not loc
        (str "Could not find city: " city)
        (json/generate-string {:name (:name loc) :country (:country loc)
                               :latitude (:latitude loc) :longitude (:longitude loc)})))
    (catch Exception e
      (str "Geocoding error: " (.getMessage e)))))

(defn fetch-weather [latitude longitude]
  (try
    (let [wx (-> (http/get (str "https://api.open-meteo.com/v1/jma?latitude=" latitude
                                "&longitude=" longitude
                                "&current=temperature_2m,weather_code,wind_speed_10m"
                                "&timezone=auto"))
                 :body (json/parse-string true))
          c  (:current wx)]
      (str (get wmo-codes (:weather_code c) "Unknown") ", "
           (:temperature_2m c) "°C, "
           "wind " (:wind_speed_10m c) " km/h"))
    (catch Exception e
      (str "Weather lookup error: " (.getMessage e)))))

(defn execute-tool [{:keys [name arguments]}]
  (case name
    "geocode"      (fetch-geocode (:city arguments))
    "get_weather"  (fetch-weather (:latitude arguments) (:longitude arguments))
    "search_web"   (tavily-search (:query arguments))
    "calculate"    (str "Result: "
                        (try (load-string (:expression arguments))
                             (catch Exception e (str "Error: " (.getMessage e)))))
    (str "Unknown tool: " name)))

;; ══════ Chat persistence ══════

(defn chat-path [id] (str data-dir "/" id ".edn"))

(defn save-chat [chat]
  (spit (chat-path (:id chat)) (pr-str chat)))

(defn load-chat [id]
  (let [f (io/file (chat-path id))]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn list-chats []
  (->> (.listFiles (io/file data-dir))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(edn/read-string (slurp %)))
       (sort-by :updated-at)
       reverse))

(defn delete-chat [id]
  (let [f (io/file (chat-path id))] (when (.exists f) (.delete f))))

(defn new-chat-id [] (str (java.util.UUID/randomUUID)))

;; ══════ HTML helpers ══════

(defn render-message [{:keys [role content tool-steps]}]
  (case (keyword role)
    :user
    (str (h/html [:div.msg.user [:div.bubble content]]))
    :assistant
    (str (h/html
      [:div.msg.assistant
       (when (seq tool-steps)
         [:div.tool-steps
          (for [{:keys [calls results]} tool-steps]
            [:div.tool-step
             (for [[call result] (map vector calls results)]
               [:div.tool-use
                [:div.tool-call "🛠️ " [:strong (:name call)] " " (pr-str (:arguments call))]
                [:div.tool-result "→ " result]])])])
       [:div.bubble content]]))
    ""))

(defn render-sidebar [chats active-id]
  (str (h/html
    [:div.sidebar-hdr [:h2 "Agent"] [:button.sidebar-close {:onclick "closeSidebar()"} "×"]]
    [:a.new-chat {:href "/"} "+ New chat"]
    [:div.chat-list
     (for [{:keys [id title]} chats]
       [:div.chat-item {:class (when (= id active-id) "active")}
        [:a {:href (str "/c/" id)} (or title "New chat")]
        [:button.del {:onclick (str "fetch('/c/" id "/delete',{method:'POST'}).then(()=>location.href='/')")} "×"]])])))

;; ══════ CSS ══════

(def css "
* { box-sizing:border-box; margin:0; padding:0 }
body { font-family:-apple-system,system-ui,sans-serif; background:#0a0a0a; color:#e0e0e0; height:100vh; display:flex }
a { color:#8ab4f8; text-decoration:none }
.sidebar { width:260px; background:#111; border-right:1px solid #222; display:flex; flex-direction:column; height:100vh; overflow-y:auto }
.sidebar-hdr { padding:16px; display:flex; justify-content:space-between; align-items:center }
.sidebar-hdr h2 { font-size:16px; color:#aaa }
.sidebar-close { display:none; background:none; border:none; color:#888; font-size:20px; cursor:pointer }
.new-chat { display:block; margin:8px 12px; padding:10px; background:#1a1a2e; border:1px solid #333; border-radius:8px; text-align:center; font-size:14px }
.new-chat:hover { background:#222240 }
.chat-list { flex:1 }
.chat-item { display:flex; align-items:center; padding:4px 12px }
.chat-item a { flex:1; padding:8px; border-radius:6px; font-size:13px; color:#ccc; overflow:hidden; white-space:nowrap; text-overflow:ellipsis }
.chat-item.active a { background:#1a1a2e; color:#fff }
.chat-item:hover a { background:#1a1a2e }
.chat-item .del { background:none; border:none; color:#555; cursor:pointer; font-size:14px; padding:4px 8px; opacity:0 }
.chat-item:hover .del { opacity:1 }
.main { flex:1; display:flex; flex-direction:column; height:100vh }
.messages { flex:1; overflow-y:auto; padding:20px; max-width:800px; margin:0 auto; width:100% }
.msg { margin:8px 0; display:flex }
.msg.user { justify-content:flex-end }
.msg.user .bubble { background:#1a3a5c; border-radius:16px 16px 4px 16px; padding:10px 16px; max-width:70%; white-space:pre-wrap }
.msg.assistant { flex-direction:column }
.msg.assistant .bubble { background:#1a1a2e; border-radius:16px 16px 16px 4px; padding:10px 16px; max-width:85%; white-space:pre-wrap }
.tool-steps { margin-bottom:6px }
.tool-step { margin:4px 0 }
.tool-use { background:#111; border:1px solid #2a2a3a; border-radius:8px; padding:8px 12px; margin:2px 0; font-size:12px; font-family:monospace }
.tool-call { color:#b8d4f0 }
.tool-result { color:#7a9; margin-top:2px }
.input-row { display:flex; padding:12px 20px; border-top:1px solid #222; max-width:800px; margin:0 auto; width:100%; gap:8px }
.input-row textarea { flex:1; background:#111; color:#e0e0e0; border:1px solid #333; border-radius:8px; padding:10px 14px; font-size:15px; resize:none; font-family:inherit; min-height:44px }
.input-row textarea:focus { outline:none; border-color:#555 }
.input-row button { background:#2563eb; color:white; border:none; border-radius:8px; padding:10px 20px; font-size:15px; cursor:pointer }
.input-row button:hover { background:#1d4ed8 }
.input-row button:disabled { background:#333; cursor:not-allowed }
.thinking { color:#888; font-style:italic; padding:8px 16px; font-size:13px }
.hamburger { display:none; position:fixed; top:10px; left:10px; z-index:100; background:#222; border:1px solid #444; color:#e0e0e0; font-size:20px; padding:6px 10px; border-radius:6px; cursor:pointer }
.overlay { display:none; position:fixed; inset:0; background:rgba(0,0,0,0.6); z-index:49 }
@media(max-width:768px) {
  .hamburger { display:block }
  .sidebar { position:fixed; left:-270px; z-index:50; transition:left .2s }
  .sidebar.open { left:0 }
  .sidebar-close { display:block }
  .overlay.open { display:block }
  .input-row textarea { font-size:16px }
}
")

;; ══════ Client JS ══════

(def client-js (str
  "function send(){"
  "var ta=document.getElementById('msg');var msg=ta.value.trim();"
  "if(!msg)return;ta.value='';ta.disabled=true;"
  "document.getElementById('send-btn').disabled=true;"
  "var form=document.getElementById('form');var url=form.action;"
  "fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
  "body:'message='+encodeURIComponent(msg)})"
  ".then(function(r){return r.json()})"
  ".then(function(d){"
  "  document.getElementById('sidebar').innerHTML=d.sidebarHtml;"
  "  var msgs=document.getElementById('messages');"
  "  msgs.innerHTML+=d.userHtml;"
  "  msgs.innerHTML+='<div class=\"thinking\" id=\"status\">Thinking...</div>';"
  "  scrollDown();"
  "  var es=new EventSource('/c/'+d.chatId+'/stream');"
  "  var buf='';"
  "  es.addEventListener('step',function(e){"
  "    var s=document.getElementById('status');"
  "    if(s)s.innerHTML=e.data;"
  "  });"
  "  es.addEventListener('chunk',function(e){"
  "    var s=document.getElementById('status');if(s)s.remove();"
  "    buf+=e.data.replace(/\\n/g,'\\n');"
  "    var el=document.getElementById('assistant-streaming');"
  "    if(!el){msgs.innerHTML+='<div class=\"msg assistant\"><div class=\"bubble\" id=\"assistant-streaming\"></div></div>';el=document.getElementById('assistant-streaming');}"
  "    el.textContent=buf;scrollDown();"
  "  });"
  "  es.addEventListener('done',function(e){"
  "    es.close();"
  "    var s=document.getElementById('status');if(s)s.remove();"
  "    ta.disabled=false;document.getElementById('send-btn').disabled=false;ta.focus();"
  "    var d2=JSON.parse(e.data);"
  "    msgs.innerHTML=d2.messagesHtml;scrollDown();"
  "    if(window.history.replaceState)window.history.replaceState(null,null,'/c/'+d2.chatId);"
  "    document.getElementById('form').action='/c/'+d2.chatId+'/send';"
  "  });"
  "  es.onerror=function(){es.close();ta.disabled=false;document.getElementById('send-btn').disabled=false;};"
  "})}"
  "function scrollDown(){var el=document.getElementById('messages');el.scrollTop=el.scrollHeight;}"
  "function toggleSidebar(){document.getElementById('sidebar').classList.toggle('open');document.getElementById('overlay').classList.toggle('open');}"
  "function closeSidebar(){document.getElementById('sidebar').classList.remove('open');document.getElementById('overlay').classList.remove('open');}"
  ))

;; ══════ Page ══════

(defn page [chat-id]
  (let [chat (when chat-id (load-chat chat-id))
        messages (or (:messages chat) [])]
    (str (h/html
      (raw-string "<!DOCTYPE html>")
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title "clj-llm agent"]
        [:style (raw-string css)]]
       [:body
        [:button.hamburger {:onclick "toggleSidebar()"} "☰"]
        [:div#overlay.overlay {:onclick "closeSidebar()"}]
        [:div#sidebar.sidebar (raw-string (render-sidebar (list-chats) chat-id))]
        [:div.main
         [:div#messages.messages
          (if (empty? messages)
            [:div {:style "text-align:center;color:#555;margin-top:40vh"}
             [:p {:style "font-size:18px"} "🤖 Agent with tools"]
             [:p {:style "font-size:13px;margin-top:8px;color:#444"}
              "I can check weather, search the web, and do math."]]
            (raw-string (apply str (map render-message messages))))]
         [:form#form.input-row {:method "POST" :action (str "/c/" (or chat-id "new") "/send")}
          [:textarea#msg {:name "message" :placeholder "Ask me anything..." :rows 1 :autofocus true
                          :onkeydown "if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();send()}"}]
          [:button#send-btn {:type "button" :onclick "send()"} "Send"]]
         [:script (raw-string client-js)]]]]))))

;; ══════ Pending responses (for SSE) ══════

(def pending (atom {}))

(defn sse-event [event-name data]
  (str "event: " event-name "\ndata: " data "\n\n"))

;; ══════ Handler ══════

(defn parse-form [body]
  (when body
    (let [s (if (string? body) body (slurp body))]
      (->> (str/split s #"&")
           (map #(str/split % #"=" 2))
           (map (fn [[k v]] [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))
           (into {})))))

(defn handler [{:keys [uri request-method body] :as req}]
  (cond
    ;; Home
    (and (= uri "/") (= request-method :get))
    {:status 200 :headers {"Content-Type" "text/html"} :body (page nil)}

    ;; View chat
    (and (re-matches #"/c/[^/]+" uri) (= request-method :get))
    (let [chat-id (subs uri 3)]
      {:status 200 :headers {"Content-Type" "text/html"} :body (page chat-id)})

    ;; Delete chat
    (and (re-matches #"/c/[^/]+/delete" uri) (= request-method :post))
    (let [chat-id (subs uri 3 (- (count uri) 7))]
      (delete-chat chat-id)
      {:status 200 :headers {"Content-Type" "application/json"} :body "{}"})

    ;; Send message
    (and (re-matches #"/c/[^/]+/send" uri) (= request-method :post))
    (let [chat-id-raw (subs uri 3 (- (count uri) 5))
          chat-id (if (= chat-id-raw "new") (new-chat-id) chat-id-raw)
          {:keys [message]} (parse-form body)
          chat (or (load-chat chat-id) {:id chat-id :messages [] :llm-history [] :created-at (System/currentTimeMillis)})
          user-msg {:role :user :content message}
          chat (-> chat
                   (update :messages conj user-msg)
                   (update :llm-history conj {:role "user" :content message})
                   (assoc :updated-at (System/currentTimeMillis)))
          chat (if (:title chat) chat (assoc chat :title (subs message 0 (min 50 (count message)))))]
      (save-chat chat)
      (swap! pending assoc chat-id {:chat chat :message message})
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
               {:chatId chat-id
                :userHtml (render-message user-msg)
                :sidebarHtml (render-sidebar (list-chats) chat-id)})})

    ;; SSE stream — run the agent loop
    (and (re-matches #"/c/[^/]+/stream" uri) (= request-method :get))
    (let [chat-id (subs uri 3 (- (count uri) 7))
          {:keys [chat]} (get @pending chat-id)]
      (swap! pending dissoc chat-id)
      (if-not chat
        {:status 404 :body "no pending"}
        (hk/as-channel req
          {:on-open
           (fn [ch]
             (hk/send! ch {:status 200
                           :headers {"Content-Type" "text/event-stream"
                                     "Cache-Control" "no-cache"
                                     "X-Accel-Buffering" "no"}} false)
             (future
               (try
                 (let [max-steps 5]
                   (loop [history (vec (:llm-history chat))
                          step-displays []
                          n 0]
                     (let [result (reduce
                                     (fn [acc event]
                                       (case (:type event)
                                         :content (update acc :chunks conj (:content event))
                                         :tool-call (update acc :tool-calls conj
                                                      (assoc event :arguments (or (:arguments event) "")))
                                         :tool-call-delta
                                         (let [idx (:index event)
                                               pos (get-in acc [:tc-pos idx])]
                                           (if pos
                                             (update-in acc [:tool-calls pos :arguments] str (:arguments event))
                                             acc))
                                         acc))
                                     {:chunks [] :tool-calls [] :tc-pos {}}
                                     (llm/request ai {:tools agent-tools} history))
                           text (apply str (:chunks result))
                           raw-tc (:tool-calls result)
                           tc (when (seq raw-tc)
                                (mapv (fn [t]
                                        (let [args (try (json/parse-string (:arguments t) true)
                                                       (catch Exception _ (:arguments t)))]
                                          {:id (:id t) :name (:name t) :arguments args}))
                                      raw-tc))]
                       (if (seq tc)
                         ;; Tool calls — execute and loop
                         (if (>= (inc n) max-steps)
                           (do (hk/send! ch (sse-event "chunk" "(max tool steps reached)") false)
                               (hk/close ch))
                           (let [tool-calls tc
                                 tool-results (mapv (fn [tc] {:call tc :result (execute-tool tc)}) tool-calls)
                                 step-html (str "<div class='tool-step'>"
                                               (apply str
                                                 (map (fn [{:keys [call result]}]
                                                        (str "<div class='tool-use'>"
                                                             "<div class='tool-call'>🛠️ <strong>" (:name call) "</strong> " (pr-str (:arguments call)) "</div>"
                                                             "<div class='tool-result'>→ " result "</div></div>"))
                                                      tool-results))
                                               "</div>")
                                 result-msgs (mapv (fn [{:keys [call result]}]
                                                     (llm/tool-result (:id call) (str result)))
                                                   tool-results)
                                 new-history (let [msg {:role :assistant
                                                     :tool-calls (mapv (fn [{:keys [id name arguments]}]
                                                                        {:id id :type "function"
                                                                         :function {:name name
                                                                                    :arguments (if (string? arguments)
                                                                                                 arguments
                                                                                                 (json/generate-string arguments))}})
                                                                      tool-calls)}]
                                   (into (conj history msg) result-msgs))]
                             (hk/send! ch (sse-event "step" step-html) false)
                             (recur new-history
                                    (conj step-displays {:calls (vec tool-calls)
                                                         :results (mapv :result tool-results)})
                                    (inc n))))
                         ;; Text response — stream char by char for effect
                         (let [text (or text "")]
                           (doseq [chunk (partition-all 4 text)]
                             (hk/send! ch (sse-event "chunk" (apply str chunk)) false)
                             (Thread/sleep 12))
                           (let [assistant-msg {:role :assistant :content text :tool-steps step-displays}
                                 final-history (conj history {:role "assistant" :content text})
                                 final-chat (-> chat
                                                (update :messages conj assistant-msg)
                                                (assoc :llm-history final-history)
                                                (assoc :updated-at (System/currentTimeMillis)))
                                 _ (save-chat final-chat)
                                 all-html (apply str (map render-message (:messages final-chat)))]
                             (hk/send! ch (sse-event "done"
                                            (json/generate-string {:chatId chat-id :messagesHtml all-html})) false)
                             (hk/close ch)))))))
                 (catch Exception e
                   (println "Agent error:" (.getMessage e))
                   (try
                     (hk/send! ch (sse-event "chunk" (str "Error: " (.getMessage e))) false)
                     (hk/close ch)
                     (catch Exception _))))))})))

    :else {:status 404 :body "not found"}))

;; ══════ Start ══════

(println (str "🤖 Agent on http://localhost:" port))
(hk/run-server handler {:port port})
@(promise)
