(ns scratch.calendar
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [scratch.meetings :as meetings])
  (:import [java.time Instant LocalDateTime ZoneId ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(defn start [meeting]
  (let [local (LocalDateTime/parse (str (:date meeting) "T" (:time meeting)))
        zone (ZoneId/of (:timezone meeting))
        offsets (.getValidOffsets (.getRules zone) local)]
    (when (not= 1 (count offsets))
      (throw (ex-info "Meeting time is missing or ambiguous during a daylight-saving transition; choose an unambiguous local time"
                      {:id (:id meeting) :date (:date meeting) :time (:time meeting) :timezone (:timezone meeting)})))
    (.atZone local zone)))

(defn enrich [meeting]
  (let [time (start meeting)]
    (assoc meeting
           :start-utc (str (.toInstant time))
           :end-utc (str (.toInstant (.plusMinutes time (:duration-minutes meeting))))
           :utc-offset (if (= ZoneOffset/UTC (.getOffset time)) "UTC" (str "UTC" (.getOffset time)))
           :date-label (.format time (DateTimeFormatter/ofPattern "EEE d MMM uuuu" Locale/ENGLISH)))))

(defn escape-text [text]
  (-> text
      (str/replace "\\" "\\\\")
      (str/replace #"\r\n|\r|\n" (constantly "\\n"))
      (str/replace ";" "\\;")
      (str/replace "," "\\,")))

(defn fold-line [line]
  (let [bytes (.getBytes line "UTF-8")
        length (alength bytes)]
    (loop [offset 0 lines []]
      (if (= offset length)
        (str/join "\r\n" lines)
        (let [prefix (if (zero? offset) "" " ")
              end (loop [end (min length (+ offset (- 75 (count prefix))))]
                    (if (and (< end length) (= 128 (bit-and (aget bytes end) 192)))
                      (recur (dec end))
                      end))]
          (recur end (conj lines (str prefix (String. bytes offset (- end offset) "UTF-8")))))))))

(defn utc [instant]
  (.format (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") ZoneOffset/UTC)
           (Instant/parse instant)))

(defn document [data meeting]
  (when-not (meetings/calendar? meeting)
    (throw (ex-info "Only real scheduled meetings can generate calendar invitations"
                    {:id (:id meeting) :status (:status meeting)})))
  (let [meeting (enrich meeting)
        repo (get-in data [:site :repository])]
    (str
     (str/join "\r\n"
               (map fold-line
                    (concat
                     ["BEGIN:VCALENDAR" "VERSION:2.0" "PRODID:-//scratch-buffer//Emacs User Group//EN"
                      "CALSCALE:GREGORIAN" "METHOD:PUBLISH" "BEGIN:VEVENT"
                      (str "UID:" (escape-text (str repo "#" (:id meeting))))
                      (str "DTSTAMP:" (utc (str (:today data) "T00:00:00Z")))
                      (str "DTSTART:" (utc (:start-utc meeting)))
                      (str "DTEND:" (utc (:end-utc meeting)))
                      (str "SUMMARY:" (escape-text (:title meeting)))
                      (str "DESCRIPTION:" (escape-text (str/join "\n\n" (concat [(:description meeting)]
                                                                               (:body meeting)
                                                                               (when (:join-url meeting) [(:join-url meeting)])))))]
                     (when-let [location (:location meeting)] [(str "LOCATION:" (escape-text location))])
                     [(str "URL:" (or (:join-url meeting) (str repo "/blob/main/content/meetings/" (:id meeting) ".edn")))
                      "STATUS:CONFIRMED" "END:VEVENT" "END:VCALENDAR"])))
     "\r\n")))

(defn publish! [out data]
  (doseq [meeting (:meetings data) :when (meetings/calendar? meeting)
          :let [path (str out (meetings/calendar-path meeting))]]
    (fs/create-dirs (fs/parent path))
    (spit path (document data meeting))))
