(ns scratch.actions
  (:require [nexus.core :as nexus]
            [scratch.effects :as effects]
            [scratch.state :as state]))

(def action-ids
  [:buffer/open :buffer/previous :minibuffer/open :minibuffer/cancel
   :minibuffer/input :minibuffer/move :minibuffer/select :minibuffer/history
   :minibuffer/execute :command/execute :theme/set :theme/toggle :theme/system
   :theme/restore :github/open :github/load-snapshot :github/snapshot-loaded :github/snapshot-failed
   :teams/open :tip/random :tip/set :tip/expire :tip/dismiss :tip/pause :tip/resume
   :ui/scroll :ui/notice :ui/dismiss :hero/select
   :member/copy-template :code/copy :meeting/download :meetings/prepare :meetings/local-times
   :constellation/advance :constellation/commit :constellation/toggle :constellation/reduced
   :constellation/available :constellation/visible :constellation/foreground
   :orbit-word/advance :orbit-word/commit
   :pwa/offline :pwa/ready :pwa/available :pwa/install])

(def nexus-config
  {:nexus/system->state deref
   :nexus/placeholders
   {:event/value (fn [data] (some-> (:replicant/dom-event data) .-target .-value))}
   :nexus/expansions
   (into {} (for [kind action-ids]
              [kind (fn [app-state & args]
                      (let [{:keys [state effects]} (state/transition app-state (into [kind] args))]
                        (into [[:state/replace state]] effects)))]))
   :nexus/effects effects/handlers
   :nexus/on-error
   (fn [_ error] (js/console.error "Action failed." (:err error)))})

(defn dispatch! [app data actions]
  (let [result (nexus/dispatch nexus-config app data actions)]
    (when (seq (:errors result))
      (swap! app assoc-in [:ui :notice] "That action could not be completed. Try again, or use the ordinary page links."))
    result))
