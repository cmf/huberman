(ns huberman.core
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [net.cgrand.xforms :as x])
  (:import (java.io File FilenameFilter)
           (java.util TreeMap Comparator)))

(def episode-title #"(.*) _ Huberman Lab.*#(\d+) \[[^\]]+\](\.[^\.]+\.vtt)")

(def new-timestamp-format #"(\d\d):(\d\d):(\d\d)\s+(.*)")
(def old-timestamp-format-1 #"([^\-]+)\s+(\-\s+)?((\d+):)?(\d\d):(\d\d)")
(def old-timestamp-format-2 #"((\d+):)?(\d\d):(\d\d)\s+(-\s+)?(.*)")

(defn timestamp [line]
  (if-let [[_ h m s title]
           (re-matches new-timestamp-format line)]
    {:h     (Integer/parseInt h)
     :m     (Integer/parseInt m)
     :s     (Integer/parseInt s)
     :title title}
    (if-let [[_ title _ _ h m s]
             (re-matches old-timestamp-format-1 line)]
      {:h     (if h (Integer/parseInt h) 0)
       :m     (Integer/parseInt m)
       :s     (Integer/parseInt s)
       :title title}
      (if-let [[_ _ h m s _ title]
               (re-matches old-timestamp-format-2 line)]
        {:h     (if h (Integer/parseInt h) 0)
         :m     (Integer/parseInt m)
         :s     (Integer/parseInt s)
         :title title}))))

(comment
  (re-matches new-timestamp-format "00:36:50 Set the Right Visual Window Size")
  (re-matches old-timestamp-format-1 "Jennifer Aniston Neurons 13:30")
  (re-matches old-timestamp-format-1 "When To Eat - 54:00")
  (re-matches old-timestamp-format-1 "Using The Body To Control The Mind - 1:08:00")
  (re-matches old-timestamp-format-2 "54:05 - Smart Drugs")
  (re-matches old-timestamp-format-2 "1:01:10 - Magnesium: Yay, Nay, or Meh?")
  (re-matches old-timestamp-format-2 "00:00 Introduction")
  (re-matches old-timestamp-format-2 "1:02:15 Booze / Weed"))

(defn subtitle-files []
  ; Find the subtitle files in the format left by yt-dlp
  (let [info (into []
                   (map (fn [^File file]
                          (let [name (.getName file)]
                            (or (re-matches episode-title name)
                                (throw (ex-info "Cannot parse filename"
                                                {:file-name name}))))))
                   (.listFiles (File. (System/getProperty "user.dir"))
                               (reify FilenameFilter
                                 (accept [this dir name]
                                   (str/ends-with? name ".vtt")))))]
    ; info contains [filename episode-title episode-number-string subtitle-file-extension]
    ; Sort them by the episode number
    (sort-by #(Integer/parseInt (nth % 2)) info)))


(defn episode-timestamps [[filename _ _ extension]]
  (let [description-filename (str (subs filename 0 (- (count filename) (count extension)))
                                  ".description")]
    (into []
          (filter timestamp)
          (line-seq (io/reader (io/file description-filename))))))


(def subtitle-timestamp #"(\d\d):(\d\d):(\d\d)\.(\d+)\s+-->\s+\d\d:\d\d:\d\d\.\d+")

(defn compare-timestamp [ts1 ts2]
  (let [ret (compare (:h ts1) (:h ts2))
        ret (if (zero? ret)
              (compare (:m ts1) (:m ts2))
              ret)
        ret (if (zero? ret)
              (compare (:s ts1) (:s ts2))
              ret)
        ret (if (zero? ret)
              (compare (or (:ms ts1) 0) (or (:ms ts2) 0))
              ret)
        ret (if (zero? ret)
              (compare (or (:index ts1) 0) (or (:index ts2) 0))
              ret)]
    ret))

(defn subtitle-lines [file-name]
  (into []
        (comp
          (remove str/blank?)
          (partition-by #(boolean (re-matches subtitle-timestamp %)))
          (drop 1)
          (x/partition 2)
          (map (fn [[[timestamp-line] lines]]
                 (let [[_ h m s ms] (re-matches subtitle-timestamp timestamp-line)]
                   [{:h  (Integer/parseInt h)
                     :m  (Integer/parseInt m)
                     :s  (Integer/parseInt s)
                     :ms (Integer/parseInt ms)}
                    (str/join \space lines)]))))
        (line-seq (io/reader file-name))))

(defn write-markdown [info]
  (let [[file-name episode-title episode-number-string _] info
        timestamp-lines (episode-timestamps info)
        subtitles (subtitle-lines file-name)
        ; We need to use TreeMap here rather than sorted-map because it
        ; implements NavigableMap
        ; This code is unfortunately fairly mutable as a result
        result (TreeMap. (reify Comparator
                           (compare [this o1 o2]
                             (compare-timestamp o1 o2))))
        episode-number (Integer/parseInt episode-number-string)
        markdown-filename (format "%03d. %s.md" episode-number episode-title)]
    (doseq [[k v] subtitles]
      (.put result k v))
    ; Some tricky code here. The section timestamps are not very accurate, so
    ; we ensure that they're not placed in the middle of a sentence. We look at
    ; where we would have placed the section in the subtitles, and if the next
    ; line doesn't start with a capital letter, we swap the section break with
    ; preceding lines until the line after the section does start with a capital
    ; letter.
    (doseq [line timestamp-lines]
      (let [{:keys [h m s title]} (timestamp line)]
        (loop [key {:h h :m m :s s}]
          ; Find the next subtitle line after our section timestamp
          (let [[k v] (.ceilingEntry result key)]
            ; If that line starts with a capital letter
            (if (Character/isUpperCase (int (first (filter #(Character/isAlphabetic (int %)) v))))
              ; We're all good, just add the section to the map
              (if (zero? (compare-timestamp k key))
                ; Ensure we don't overwrite an entry, and put the section first
                (do (.put result key (str "### " title))
                    (.put result (assoc key :index 1) v))
                (.put result key (str "### " title)))
              ; Otherwise find the previous line
              (if-let [[k2 v2] (.lowerEntry result key)]
                (do
                  ; Move the previous line to use the section's timestamp
                  (.remove result k2)
                  (.put result key v2)
                  ; And try to add the section again with the previous line's timestamp
                  (recur k2))
                ; No previous entry found, just insert the section here. This can happen when
                ; subtitles look like "[upbeat music] - Welcome" or similar edge cases
                (if (zero? (compare-timestamp k key))
                  ; Ensure we don't overwrite an entry, and put the section first
                  (do (.put result key (str "### " title))
                      (.put result (assoc key :index 1) v))
                  (.put result key (str "### " title)))))))))
    (spit markdown-filename
          (str "# " episode-title "\n"
               "\n"
               "## Huberman Lab #" episode-number "\n"
               "\n"
               (str/join "\n"
                         (map (fn [line]
                                (cond
                                  (nil? line) nil

                                  ; sometimes starting with a - means a break, e.g. change of speaker
                                  (str/starts-with? line "- ")
                                  (str "\n" (subs line 2))

                                  (str/starts-with? line "###")
                                  (str "\n" line "\n")

                                  :else line))
                              (vals result)))))))


(comment
  (let [info (re-matches episode-title
                         "Effects of Fasting & Time Restricted Eating on Fat Loss & Health _ Huberman Lab Podcast #41 [9tRohh0gErM].en.vtt")]
    (write-markdown info)))

(defn process-files [& args]
  (doseq [info (subtitle-files)]
    (write-markdown info)))

(comment
  (process-files)

  (into []
        (map (fn [info]
               (let [[file-name episode-title episode-number-string _] info
                     timestamps (episode-timestamps info)]
                 (if (empty? timestamps)
                   (throw (ex-info "Unable to find timestamps"
                                   {:file-name file-name}))
                   {:episode-title  episode-title
                    :episode-number (Integer/parseInt episode-number-string)
                    :timestamps     timestamps}))))
        (subtitle-files))

  (re-matches subtitle-timestamp "02:17:56.104 --> 02:17:58.687")
  (subtitle-info))
