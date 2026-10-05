(ns scratch.pwa
  (:require [scratch.effects :as effects]
            [scratch.router :as router]))

(defn watch-worker! [registration dispatch]
  (when-let [worker (.-installing registration)]
    (.addEventListener
     worker "statechange"
     (fn [_]
       (cond
         (= "redundant" (.-state worker))
         (do (js/console.error "The offline edition could not be cached.")
             (dispatch [[:ui/notice "Offline reading could not be prepared. Published pages still work while connected."]]))

         (and (= "installed" (.-state worker))
              (.-controller (.-serviceWorker js/navigator)))
         (dispatch [[:ui/notice "An updated site is ready. Close this site’s tabs and reopen it to use the new cached edition."]]))))))

(defn start! [app dispatch]
  (dispatch [[:pwa/offline (not (.-onLine js/navigator))]])
  (.addEventListener js/window "online" #(dispatch [[:pwa/offline false]]))
  (.addEventListener js/window "offline" #(dispatch [[:pwa/offline true]]))
  (.addEventListener js/window "beforeinstallprompt"
                     (fn [event]
                       (.preventDefault event)
                       (reset! effects/install-event event)
                       (dispatch [[:pwa/available true]])))
  (.addEventListener js/window "appinstalled"
                     (fn [_]
                       (reset! effects/install-event nil)
                       (dispatch [[:pwa/available false] [:ui/notice "Installed. See you in the next buffer."]])))
  (when (.-serviceWorker js/navigator)
    (-> (.register (.-serviceWorker js/navigator) (router/url (:base @app) "/sw.js")
                   #js {:scope (:base @app)})
        (.then (fn [registration]
                 (watch-worker! registration dispatch)
                 (.addEventListener
                  registration "updatefound"
                  (fn [_] (watch-worker! registration dispatch)))
                 (.-ready (.-serviceWorker js/navigator))))
        (.then (fn [_] (dispatch [[:pwa/ready]])))
        (.catch (fn [error]
                  (js/console.error "Offline cache registration failed." error)
                  (dispatch [[:ui/notice "Offline reading could not be prepared. Published pages still work while connected."]]))))))
