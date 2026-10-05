(ns scratch.app
  (:require [replicant.dom :as r]
            [scratch.actions :as actions]
            [scratch.effects :as effects]
            [scratch.keyboard :as keyboard]
            [scratch.motion :as motion]
            [scratch.nav :as nav]
            [scratch.pwa :as pwa]
            [scratch.router :as router]
            [scratch.state :as state]
            [scratch.view :as view]))

(def base (.getAttribute (.querySelector js/document "meta[name='app-base']") "content"))
(defonce app
  (let [now (.toISOString (js/Date.))]
    (atom (assoc-in (state/initial (assoc scratch.generated/data :today (subs now 0 10) :now now)
                                  base (or (router/local-path base js/location.pathname) js/location.pathname))
                    [:ui :hero] (rand-nth [:constellation :parentheses])))))
(defn dispatch [events] (actions/dispatch! app {} events))
(defn render! [] (r/render (.getElementById js/document "app") (view/view @app)))

(defn update-scroll! []
  (let [label (effects/scroll-position)]
    (when (not= label (get-in @app [:ui :scroll])) (dispatch [[:ui/scroll label]]))))

(r/set-dispatch! #(actions/dispatch! app %1 %2))
(add-watch app :render (fn [_ _ _ _] (render!)))
(render!)
(.setAttribute (.-documentElement js/document) "data-enhanced" "true")
(set! (.-scrollRestoration js/history) "manual")

(let [media (.matchMedia js/window "(prefers-color-scheme: dark)")]
  (dispatch [[:theme/system (.-matches media)]])
  (.addEventListener media "change" #(dispatch [[:theme/system (.-matches %)]])))

(try
  (when-let [theme (.getItem js/localStorage "scratch-theme")]
    (dispatch [[:theme/restore (keyword theme)]]))
  (catch :default e
    (js/console.warn "Theme preference unavailable." e)
    (dispatch [[:ui/notice "This browser cannot read saved preferences. Your device theme is in use."]])))

(.addEventListener js/window "popstate"
                   (fn [_]
                     (dispatch [[:buffer/open (str (or (router/local-path base js/location.pathname) "/") js/location.hash)
                                 {:pop? true :restore? true :scroll js/window.scrollY}]])))
(.addEventListener js/document "keydown" #(keyboard/handle! app dispatch %))
(.addEventListener js/window "scroll" update-scroll! #js {:passive true})
(.addEventListener js/window "resize" update-scroll! #js {:passive true})
(js/requestAnimationFrame (fn [_] (update-scroll!)))
(dispatch [[:meetings/prepare] [:github/load-snapshot]])
(motion/start! app dispatch)
(nav/start! app)
(pwa/start! app dispatch)

(when (or (seq js/location.hash)
          (not= js/location.pathname (router/url base (get-in @app [:route :path]))))
  (dispatch [[:buffer/open (str (or (router/local-path base js/location.pathname) "/") js/location.hash)
              {:replace? true}]]))

(defonce console-welcome
  (do
    (js/console.info
     (str "\n"
          "        \\  |  /\n"
          "         \\ | /\n"
          "      ---- * ----\n"
          "         / | \\\n"
          "        /  |  \\_\n"
          "\n"
          "Welcome to *scratch-buffer*.\n"
          "A small website. A perfectly reasonable amount of Lisp.\n"
          "M-x is downstairs. Your yak can wait.\n"))
    true))
