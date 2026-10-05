(ns scratch.keyboard
  (:require [clojure.string :as str]))

(defonce prefix-until (atom 0))

(defn editable? [e]
  (let [target (.-target e)]
    (or (.-isContentEditable target)
        (#{"INPUT" "TEXTAREA" "SELECT"} (.-tagName target)))))

(defn handle! [app dispatch e]
  (let [key (str/lower-case (.-key e))
        open? (get-in @app [:minibuffer :open?])
        palette-input? (= "command-input" (.-id (.-target e)))
        control? (and (.-ctrlKey e) (not (or (.-altKey e) (.-metaKey e))))
        meta? (and (.-altKey e) (not (or (.-ctrlKey e) (.-metaKey e))))
        plain? (not (or (.-altKey e) (.-ctrlKey e) (.-metaKey e) (.-shiftKey e)))
        prefix? (> @prefix-until (.now js/Date))
        action
        (cond
          (.-isComposing e) nil
          (and meta? (= key "x")) [:minibuffer/open :commands]
          (and open? (or (= key "escape") (and control? (= key "g")))) [:minibuffer/cancel]
          (and palette-input? open? (= key "arrowdown")) [:minibuffer/move 1]
          (and palette-input? open? (= key "arrowup")) [:minibuffer/move -1]
          (and palette-input? open? (= key "enter")) [:minibuffer/execute]
          (and palette-input? open? meta? (= key "p")) [:minibuffer/history 1]
          (and palette-input? open? meta? (= key "n")) [:minibuffer/history -1]
          (and prefix? plain? (= key "b") (not (editable? e))) [:minibuffer/open :buffers]
          (and control? (= key "s") (not (editable? e))) [:minibuffer/open :search]
          (and control? (= key "x") (not (editable? e))) :prefix
          :else nil)]
    (when prefix? (reset! prefix-until 0))
    (when action
      (.preventDefault e)
      (if (= action :prefix)
        (reset! prefix-until (+ (.now js/Date) 1500))
        (dispatch [action])))))
