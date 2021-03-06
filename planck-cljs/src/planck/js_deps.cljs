(ns planck.js-deps
  (:require
   [cljs.tools.reader :as r]
   [clojure.string :as string]))

(defonce js-lib-index (volatile! {}))

(defn add-js-lib
  "Adds a js lib to the index."
  [index {:keys [provides] :as js-lib}]
  (reduce (fn [index provided-lib]
            (assoc index (symbol provided-lib) js-lib))
    index
    provides))

(defn add-js-libs
  "Adds multiple js libs to the index."
  [index js-libs]
  (reduce add-js-lib index js-libs))

(defn parse-closure-ns
  "Parses a js source file which uses the closure module system."
  [source]
  (->> source
    (string/split-lines)
    (mapcat #(string/split % #";"))
    (map string/trim)
    (take-while #(not (re-matches #".*=[\s]*function\(.*\)[\s]*[{].*" %)))
    (keep #(re-matches #".*goog\.(provide|require)\(['\"](.*)['\"]\)" %))
    (map rest)
    (reduce (fn [m ns]
              (let [munged-ns (string/replace (last ns) "_" "-")]
                (update m (if (= (first ns) "require")
                            :requires
                            :provides)
                  conj munged-ns)))
      {:requires [] :provides []})))

(defn file-seq
  "A tree seq on files"
  [dir]
  (tree-seq
    (fn [f] (js/PLANCK_IS_DIRECTORY f))
    (fn [d] (vec (js/PLANCK_LIST_FILES d)))
    dir))

(defn parse-libs
  "Converts a closure lib path into a list of module descriptors."
  [lib]
  (->> lib
    (file-seq)
    (filter #(string/ends-with? % ".js"))
    (map (fn [file]
           (let [source (first (js/PLANCK_READ_FILE file))]
             (when-not source
               (throw (ex-info "The specified closure library does not exist" {:path file})))
             (assoc (parse-closure-ns source) :file file))))))

(defn- add-libs
  [index {:keys [libs foreign-libs]}]
  (add-js-libs index (concat foreign-libs (mapcat parse-libs libs))))

(defn index-js-libs
  "Indexes all js foreign and closure libs from each deps.cljs on the classpath."
  []
  (vswap! js-lib-index
    (fn [index]
      (reduce (fn [index [_ deps-cljs-str]]
                (add-libs index (r/read-string deps-cljs-str)))
        index
        (js/PLANCK_LOAD_DEPS_CLJS_FILES)))))

(defn index-opts
  [opts]
  (vswap! js-lib-index add-libs opts))

(defn js-lib?
  "Returns true if the argument is a js lib."
  [dep]
  (contains? @js-lib-index dep))

(defn topo-sort
  "Returns a list of dependencies in the topological order."
  [index dep]
  (loop [ret '()
         s   #{dep}]
    (if (empty? s)
      (distinct ret)
      (let [dep      (first s)
            requires (map symbol (:requires (get index dep)))]
        (recur (conj ret dep) (into (set (rest s)) requires))))))

(defn js-libs-to-load
  "Returns a list of dependencies to load for the given lib."
  [lib]
  (let [index @js-lib-index]
    (map index (topo-sort index lib))))
