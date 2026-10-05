(ns scratch.source-maps)

(defn flatten-indexed [indexed]
  (let [{:keys [offset map]} (first (:sections indexed))
        line (:line offset)]
    (when-not (and (= 3 (:version indexed))
                   (= 1 (count (:sections indexed)))
                   (= 3 (:version map))
                   (integer? line) (<= 0 line)
                   (= 0 (:column offset))
                   (vector? (:sources map))
                   (string? (:mappings map)))
      (throw (ex-info "Expected a single zero-column source-map section"
                      {:file (:file indexed) :offset offset :sections (count (:sections indexed))})))
    ;; Each leading semicolon preserves one unmapped wrapper line in the bundle.
    (cond-> (assoc map :file (:file indexed)
                      :mappings (str (apply str (repeat line ";")) (:mappings map)))
      (:lineCount map) (update :lineCount + line))))
