(ns census.statsAPI.core
  (:require
   #?(:cljs [cljs.core.async   :refer [>! <! chan promise-chan close! take! to-chan!
                                       pipe timeout put!]
             :refer-macros [go alt!]]
      :clj [clojure.core.async :refer [>! <! chan promise-chan close! take! to-chan!
                                       pipe timeout put! go alt!]])
   [cuerdas.core       :refer [join numeric? parse-number strip-suffix]]
   [census.utils.core  :refer [$GET$ =O?>-cb xf!<< educt<< xf<<
                               transduct<<
                               filter-nil-tails
                               amap-type vec-type throw-err map-idcs-range
                               keys->strs ->args strs->keys
                               URL-WMS URL-STATS]]))

(defn kv-pair->str [[k v] separator]
  "Takes a key and a value and converts it to a string concatenated together with
  a given separator"
  (join separator [(name k) (str v)]))

(defn C-S-args->url
  "Composes a URL to call Census' statistics API"
  [{:keys [vintage sourcePath geoHierarchy values predicates statsKey]}]
  (if (not-any? nil? [vintage sourcePath values])
    (str URL-STATS
         (str vintage)
         (join (map #(str "/" %) sourcePath))
         "?get="
         (if (some? values)
           (join "," values)
           "")
         (if (some? predicates)
           (str "&" (str (join "&" (map #(kv-pair->str % "=") predicates))))
           "")
         (if (not (nil? geoHierarchy))
           (keys->strs
            (if (= 1 (count geoHierarchy))
              (str "&for=" (kv-pair->str (first geoHierarchy) ":"))
              (str "&in="  (join "%20" (map #(kv-pair->str % ":") (filter-nil-tails (butlast geoHierarchy))))
                   "&for=" (kv-pair->str (last geoHierarchy) ":"))))
           "")
         (if (not (nil? statsKey))
           (str "&key=" statsKey)))
    ""))


(defn ->valid#?->#
  "
  Conditionally translates a string into an integer or float if so coercible.
  If matches any Census error codes or isn't coericble returns the string.
  "
  [s]
  (cond (some #(= s %) ["-222222222.0000"
                        "-333333333.0000"
                        "-555555555.0000"
                        "-666666666.0000"
                        "-888888888.0000"
                        "-999999999.0000"])
        (str "NAN: " (strip-suffix s ".0000"))
        (nil? s)
        "NAN: null"
        (and (= (count s) 1) (numeric? s))
        (parse-number s)
        (and (= (subs s 0 1) "0") (not (= (subs s 1 2) ".")))
        s
        (numeric? s)
        (parse-number s)
        :else s))

;(parse-number "030381")
; Error: Invalid number: 030381

(defn xf!-CSV->CLJ
  "
  Stateful transducer, which stores the first item as a list of a keys to apply
  (via `zipmap`) to the rest of the items in a collection. Serves to turn the
  Census API response into a more conventional JSON format.
  If provided `:keywords` as an argument, will return a map with Clojure keys.
  Otherwise, will return map keys as strings.
  "
  [{:keys [values predicates]}]
  (let [parse-range [0 (+ (count values) (count predicates))]]
    (xf!<<
     (fn [state rf acc this]
       (let [Keys @state]
         (if (nil? Keys)
           (do (vreset! state (mapv strs->keys this))
               nil)
           (rf acc
               (zipmap (mapv keyword Keys)
                       (map-idcs-range ->valid#?->#
                                       parse-range
                                       this)))))))))

(defn xf-stats->js
  "Transducer, which converts results from the 'raw' Census statistical API into
  JSON (objects instead of nested arrays) with correctly parsed numbers"
  [args]
  (comp
   (xf!-CSV->CLJ args)
   (map #(clj->js % :keywordize-keys true))))

(def $url$ (atom ""))
(def $res$ (atom []))
(def $err$ (atom {}))

(def $GET$-C-stats ($GET$ :raw "Census statistics" $url$ $res$ $err$))

;(defn IOE-C->stats
;  "Internal function for calling the Census API using a Clojure Map. Returns stats
;  from Census API unaltered."
;  [=I= =O= =E=]
;  (go (let [args (<! =I=)
;            url  (C-S-args->url args)]
;        (if (= "" url)
;            (put! =E= "Invalid Census Statistics request. Please check arguments against requirements.")
;            ($GET$-C-stats (to-chan! [url]) =O= =E=)))))

(defn IOE-C-S->JS
  "Internal function for calling the Census API using a Clojure Map. Returns stats
  from Census API translated into a more verbose, but accurately typed format."
  [=I= =O= =E=]
  (take! =I=
         (fn [args]
           (let [url    (C-S-args->url args)
                 =JSON= (chan 1 (comp (educt<< (xf-stats->js args))
                                      (map to-array)))]
             (if (= "" url)
               (put! =E= "Invalid Census Statistics request. Please check arguments against requirements.")
               (do ($GET$-C-stats (to-chan! [url]) =JSON= =E=)
                   (pipe =JSON= =O=)))))))


;      e            888                       d8
;     d8b      e88~\888   /~~~8e  888-~88e  _d88__  e88~~8e  888-~\  d88~\
;    /Y88b    d888  888       88b 888  888b  888   d888  88b 888    C888
;   /  Y88b   8888  888  e88~-888 888  8888  888   8888__888 888     Y88b
;  /____Y88b  Y888  888 C888  888 888  888P  888   Y888    , 888      888D
; /      Y88b  "88_/888  "88_-888 888-_88"   '88_/  "88___/  888    \_88P
;                                 888


;; FIXME: take geoHierachy arg keys and pull the values out in order, then to str
;;(defn xf-'key'<w-stat
;;  "
;;  Takes an integer argument denoting the number of stat vars the user requested.
;;  Returns a function of one item (from the Census API response
;;  collection) to a new map with a hierarchy that will enable deep-merging of
;;  the stats with a GeoJSON `feature`s `:properties` map.
;;  "
;;  [vars#]
;;  (xf<< (fn [rf acc this]
;;          (rf acc {(apply str (vals (take-last (- (count this) vars#) this)))
;;                   {:properties this}}))))

;;  (xf<< (fn [rf acc this]
;;          (rf acc {(apply str (vals (get (- (count this) vars#) this)))
;;                   {:properties this}}))))
;;
(defn xf-'key'<w-stat
  "
  Takes the geoHierarchy portion of args and pulls out the keys, which
  is used as a path to get the values from incoming maps
  Returns a function of one item (from the Census API response
  collection) to a new map with a hierarchy that will enable deep-merging of
  the stats with a GeoJSON `feature`s `:properties` map.
  "
  [geo]
  (xf<< (fn [rf acc this]
          (rf acc {(apply str (map #(get this %) (vec (keys geo))))
                   {:properties this}}))))

#_(let [input '({:B01001_001E 55049,:state "01", :county "001"}
                {:B01001_001E 199510, :state "01", :county "003"})]
       (transduce (xf-'key'<w-stat {:state "01" :county "001"}) conj input))
; RESULT:
#_[{"01001" {:properties {:B01001_001E 55049, :state "01", :county "001"}}}
   {"01003" {:properties {:B01001_001E 199510, :state "01", :county "003"}}}]
;; Examples ==============================



(defn xf-mergeable<-stats
  "Takes users' arguments and returns a composed transducer, which converts
  the results from the Census API into a shape that allows it to be merged
  together with other data."
  [args]
  (comp
   (xf!-CSV->CLJ args)
   (xf-'key'<w-stat (get-in args [:geoHierarchy]))))


(defn =cfg=C-Stats
  "Internal function for calling Census Stats API"
  [=args= =cfg=]
  (take! =args=
         (fn [args]
           (let [geo (get args :geoHierarchy)
                 url   (C-S-args->url args)
                 xform (educt<< (xf-mergeable<-stats args))
                 s-key (keyword (first (get args :values)))]
             (if (= "" url)
               (put! =cfg= "Invalid Census Statistics request. Please check arguments against requirements.")
               (put! =cfg= {:url       url
                            :xform     xform
                            :getter    $GET$-C-stats
                            :filter-id s-key}))))))


(def cfg>cfg=C-Stats [=cfg=C-Stats false])