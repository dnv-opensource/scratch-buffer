(ns scratch.meetings)

(defn calendar? [meeting]
  (and (= :scheduled (:status meeting)) (not (:example? meeting))))

(defn calendar-path [meeting]
  (str "/meetings/" (:id meeting) "/event.ics"))

(defn epoch [instant]
  #?(:clj (.toEpochMilli (java.time.Instant/parse instant))
     :cljs (js/Date.parse instant)))

(defn next-meeting [data]
  (let [now (epoch (or (:now data) (str (:today data) "T00:00:00Z")))]
    (first (sort-by :start-utc
                   (filter #(and (calendar? %) (> (epoch (:end-utc %)) now))
                           (:meetings data))))))
