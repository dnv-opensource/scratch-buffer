(ns scratch.theme)

(defn resolved [state]
  (let [{:keys [mode system-dark?]} (:theme state)]
    (if (= mode :system) (if system-dark? :dark :light) mode)))
