(ns scratch.effects
  (:require [scratch.router :as router]
            [scratch.github :as github]
            [scratch.motion :as motion]
            [scratch.state :as state]))

(defonce return-focus (atom nil))
(defonce install-event (atom nil))
(defonce tip-timeout (atom nil))

(defn cancel-tip-expiry! []
  (when-let [timer @tip-timeout]
    (js/clearTimeout timer)
    (reset! tip-timeout nil)))

(defn event [ctx]
  (:replicant/dom-event (:dispatch-data ctx)))

(defn focus! [id]
  (when-let [element (.getElementById js/document id)]
    (.focus element #js {:preventScroll true})))

(defn after-render [f]
  (js/requestAnimationFrame (fn [_] (f))))

(defn notice! [ctx message]
  ((:dispatch ctx) [[:ui/notice message]]))

(defn scroll-position []
  (let [height (- (.-scrollHeight (.-documentElement js/document)) js/window.innerHeight)
        y js/window.scrollY]
    (cond
      (< height 2) "All"
      (< y 2) "Top"
      (>= y (- height 2)) "Bot"
      :else (str (js/Math.round (* 100 (/ y height))) "%"))))

(def handlers
  {:state/replace
   (fn [_ app value] (reset! app value))

   :constellation/fade
   (fn [_ _ index revision] (motion/fade! index revision))

   :orbit-word/fade
   (fn [_ _ revision] (motion/fade! :word revision))

   :constellation/sync
   (fn [_ _] (motion/sync!))

   :dispatch
   (fn [ctx _ action]
     ((:dispatch ctx) [(if (= :buffer/open (first action))
                        (update action 2 #(assoc % :scroll js/window.scrollY))
                        action)]))

   :link/follow
   (fn [ctx _ path]
     (let [e (event ctx)]
       (when (and e (= 0 (.-button e))
                  (not (or (.-altKey e) (.-ctrlKey e) (.-metaKey e) (.-shiftKey e))))
         (.preventDefault e)
         ((:dispatch ctx) [[:buffer/open path {:scroll js/window.scrollY}]]))))

   :history/navigate
   (fn [_ _ path options]
     (when-not (:pop? options)
       (when (not= path (str js/location.pathname js/location.hash))
         (if (:replace? options)
           (.replaceState js/history nil "" path)
           (.pushState js/history nil "" path)))))

   :document/title
   (fn [_ _ title]
     (set! (.-title js/document) (str title " · DNV *scratch-buffer*")))

   :navigation/settle
   (fn [ctx _ y anchor]
     (reset! return-focus nil)
     (after-render #(do (if-let [target (when anchor (.getElementById js/document anchor))]
                         (do (focus! anchor)
                             (.scrollIntoView target #js {:block "start" :behavior "instant"}))
                         (do (focus! "buffer")
                             (js/window.scrollTo #js {:top y :behavior "instant"})))
                        ((:dispatch ctx) [[:ui/scroll (scroll-position)]]))))

   :focus/minibuffer
   (fn [_ _]
     (when-not @return-focus (reset! return-focus (.-activeElement js/document)))
     (focus! "command-input"))

   :focus/return
   (fn [_ _]
     (let [element @return-focus]
       (reset! return-focus nil)
       (when (and element (.-isConnected element))
         (.focus element #js {:preventScroll true}))))

   :completion/scroll
   (fn [_ app]
     (after-render
      #(when-let [element (.getElementById js/document (str "candidate-" (get-in @app [:minibuffer :selection])))]
         (.scrollIntoView element #js {:block "nearest" :behavior "instant"}))))

   :theme/apply
   (fn [_ app]
     (.setAttribute (.-documentElement js/document) "data-theme" (name (get-in @app [:theme :mode])))
     (when-let [meta (.querySelector js/document "meta[name='theme-color']")]
       (.setAttribute meta "content" (if (= :dark (state/resolved-theme @app)) "#172126" "#172e35"))))

   :theme/persist
   (fn [ctx _ mode]
     (try
       (.setItem js/localStorage "scratch-theme" (name mode))
       (catch :default e
         (js/console.warn "Theme preference could not be saved." e)
         (notice! ctx "Your theme changed, but this browser could not save the preference."))))

   :external/open
   (fn [_ _ url] (.assign js/location url))

   :github/fetch-snapshot
   (fn [ctx app url revision]
     (let [controller (js/AbortController.)
           timer (js/setTimeout #(.abort controller) 12000)]
       (-> (js/fetch url #js {:credentials "omit" :cache "no-cache" :signal (.-signal controller)})
           (.then (fn [response]
                    (when-not (.-ok response)
                      (throw (js/Error. (str "Discussion snapshot request failed (HTTP " (.-status response) ")."))))
                    (.json response)))
           (.then (fn [data]
                    (let [snapshot (github/validate-snapshot! (get-in @app [:content :site :repository])
                                                              (js->clj data :keywordize-keys true))]
                      ((:dispatch ctx) [[:github/snapshot-loaded revision snapshot]]))))
           (.catch (fn [error]
                     (js/console.warn "Could not load the discussion snapshot." error)
                     ((:dispatch ctx) [[:github/snapshot-failed revision
                                        "Could not load the discussion snapshot. Retry, or read the conversation on GitHub."]])))
           (.finally #(js/clearTimeout timer)))))

   :external/open-tab
   (fn [ctx _ url]
     ;; Keep a popup handle, then let a native link enforce no-referrer navigation.
     (if-let [tab (.open js/window "about:blank" "_blank")]
       (let [document (.-document tab)
             anchor (.createElement document "a")]
         (set! (.-opener tab) nil)
         (.setAttribute anchor "href" url)
         (.setAttribute anchor "rel" "noopener noreferrer")
         (.setAttribute anchor "referrerpolicy" "no-referrer")
         (.appendChild (.-body document) anchor)
         (.click anchor))
       (do
         (js/console.warn "Opening an external tab was blocked.")
         (notice! ctx "Your browser blocked the new tab. Use the Teams chat link on this page."))))

   :clipboard/write
   (fn [ctx _ text kind]
     (let [guidance (case kind
                      :member-template "Open People to select and copy the template manually."
                      :code "Select the code and copy it manually.")
           copied (case kind
                    :member-template "Member template copied. Replace the placeholder with your GitHub handle."
                    :code "Code copied to clipboard.")]
       (if (and (.-clipboard js/navigator) (.-writeText (.-clipboard js/navigator)))
         (-> (.writeText (.-clipboard js/navigator) text)
             (.then #(notice! ctx copied))
             (.catch (fn [error]
                       (js/console.warn "Could not copy to clipboard." error)
                       (notice! ctx (str "Could not copy to clipboard. " guidance)))))
         (do
           (js/console.warn "Clipboard writing is unavailable.")
           (notice! ctx (str "Clipboard access is unavailable. " guidance))))))

   :download/file
   (fn [_ _ url filename]
     (let [anchor (.createElement js/document "a")]
       (set! (.-href anchor) url)
       (set! (.-download anchor) filename)
       (set! (.-hidden anchor) true)
       (.appendChild (.-body js/document) anchor)
       (.click anchor)
       (.remove anchor)))

   :meetings/format-local
   (fn [ctx app]
     (if (and js/Intl (.-DateTimeFormat js/Intl))
       (let [zone (.-timeZone (.resolvedOptions (js/Intl.DateTimeFormat.)))
             date (js/Intl.DateTimeFormat. "en-GB" #js {:timeZone zone :weekday "short" :day "numeric" :month "short" :year "numeric"})
             time (js/Intl.DateTimeFormat. "en-GB" #js {:timeZone zone :hour "2-digit" :minute "2-digit" :hourCycle "h23"})
             times (into {}
                         (for [meeting (get-in @app [:content :meetings])
                               :when (not= zone (:timezone meeting))
                               :let [start (js/Date. (:start-utc meeting))
                                     end (js/Date. (:end-utc meeting))]]
                           [(:id meeting) {:date (.format date start) :start (.format time start)
                                           :end-date (.format date end) :end (.format time end) :timezone zone}]))]
         ((:dispatch ctx) [[:meetings/local-times times]]))
       (do
         (js/console.warn "Local time zone formatting is unavailable.")
         (notice! ctx "Your local time zone could not be shown. Meeting times use the published time zone."))))

   :tip/choose
   (fn [ctx app]
     (let [n (count (filter :tip? (get-in @app [:content :snippets])))
           current (get-in @app [:ui :tip])
           index (if (and (> n 1) (some? current))
                   (mod (+ current 1 (rand-int (dec n))) n)
                   (rand-int n))]
       ((:dispatch ctx) [[:tip/set index]])))

   :tip/expire-later
   (fn [ctx _ revision]
     (cancel-tip-expiry!)
     (when-let [notification (.querySelector js/document ".tip-notification")]
       (when-not (.matches notification ":hover, :focus-within")
         (reset! tip-timeout
                 (js/setTimeout
                  (fn []
                    (reset! tip-timeout nil)
                    ((:dispatch ctx) [[:tip/expire revision]]))
                  6000)))))

   :tip/cancel-expiry
   (fn [_ _] (cancel-tip-expiry!))

   :tip/return-focus
   (fn [_ _] (focus! "mx-button"))

   :pwa/prompt
   (fn [ctx _]
     (if-let [prompt @install-event]
       (do
         (.prompt prompt)
         (-> (.-userChoice prompt)
             (.then (fn [choice]
                      (reset! install-event nil)
                      ((:dispatch ctx) [[:pwa/available false]])
                      (notice! ctx (if (= "accepted" (.-outcome choice))
                                     "Installation requested. Your browser will finish adding the app."
                                     "Installation canceled. You can keep reading in this browser."))))
             (.catch (fn [error]
                       (js/console.error "Installation failed." error)
                       (notice! ctx "Could not start installation. Try your browser’s install menu.")))))
       (notice! ctx
                (if (or (.-matches (.matchMedia js/window "(display-mode: standalone)"))
                        (true? (.-standalone js/navigator)))
                  "This site is already running as an installed app."
                  "To install, use your browser’s Install app menu. On iPhone or iPad, use Share → Add to Home Screen. If unavailable, bookmark this site instead."))))})
