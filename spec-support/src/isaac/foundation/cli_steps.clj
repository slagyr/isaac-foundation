(ns isaac.foundation.cli-steps
  "Foundation-grade CLI feature steps: the in-process `isaac is run with`
   wrapper plus stdout/stderr/exit assertions and root/file scaffolding. The
   wrapper depends only on foundation namespaces (main, root, shell, nexus,
   fs); server/LLM concerns (provider/HTTP/drive capture, the memory-tool
   clock) attach via the preflight/postflight/wrapper registries below, so the
   server step layer can extend the run without this namespace depending on
   them. Moves to the foundation repo at cut time."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.config.root :as root]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.shell :as shell]
    [babashka.process :as process]))

(helper! isaac.foundation.cli-steps)

;; Modules' step namespaces register preflight fns here. They run right
;; before `main/run` inside isaac-run, after Background steps have set up
;; g state. Uses a real atom — not g state — because the Background step
;; `default Grover setup` calls (g/reset!) which would otherwise clear
;; per-scenario hooks.
(defonce ^:private isaac-run-preflights* (atom []))

(defn register-isaac-run-preflight!
  "Register f to run inside isaac-run before main/run is invoked. f is a
   zero-arg function; it may inspect/modify g state. Multiple modules can
   register; all registered fns run in registration order."
  [f]
  (swap! isaac-run-preflights* conj f))

;; Symmetric to the preflight registry: postflights run right AFTER
;; main/run returns (and after :output/:stderr/:exit-code are captured),
;; so server/LLM step layers can harvest provider/HTTP/drive state into g
;; without the foundation-grade run wrapper depending on those namespaces.
(defonce ^:private isaac-run-postflights* (atom []))

(defn register-isaac-run-postflight!
  "Register f to run inside isaac-run after main/run returns. f is a
   zero-arg function; it may inspect/modify g state. Multiple modules can
   register; all registered fns run in registration order."
  [f]
  (swap! isaac-run-postflights* conj f))

;; Run-wrappers wrap the actual main/run call (innermost, inside the
;; mem-fs nexus). Each is (fn [thunk] ... (thunk) ...); they compose so a
;; server/LLM layer can inject dynamic bindings (e.g. the memory-tool
;; clock) without the foundation-grade run wrapper depending on those vars.
(defonce ^:private isaac-run-wrappers* (atom []))

(defn register-isaac-run-wrapper!
  "Register a wrapper (fn [thunk]) invoked around main/run. Wrappers
   registered earlier end up outermost. Use to bind dynamic vars for the
   duration of the run."
  [f]
  (swap! isaac-run-wrappers* conj f))

(defn- apply-run-wrappers [thunk]
  (reduce (fn [t wrap] (fn [] (wrap t))) thunk @isaac-run-wrappers*))

(defn- interpolate-args [args]
  (cond-> args
          (g/get :server-port) (str/replace "${server.port}" (str (g/get :server-port)))
          true                (str/replace "\\\"" "\"")))

(defn unescape-expected [expected]
  (-> expected
      (str/replace "\\\"" "\"")
      (str/replace "\\n" "\n")))

(defn current-output []
  (if-let [writer (g/get :live-output-writer)]
    (str writer)
    (g/get :output)))

(defn- current-stderr []
  (if-let [writer (g/get :live-error-writer)]
    (str writer)
    (g/get :stderr)))

(defn- stdout-head [text]
  (let [text (or text "")
        text (str/replace text #"\s+" " ")]
    (subs text 0 (min 120 (count text)))))

(defn- parse-path-segments [path]
  (mapv (fn [segment]
          (if (re-matches #"\d+" segment)
            (parse-long segment)
            segment))
        (str/split path #"\.")))

(defn- map-value [value segment]
  (let [keyword-segment (keyword segment)]
    (cond
      (contains? value segment)         (get value segment)
      (contains? value keyword-segment) (get value keyword-segment)
      :else                             ::missing)))

(defn- value-at-path [value path]
  (reduce (fn [current segment]
            (cond
              (= ::missing current)
              ::missing

              (integer? segment)
              (if (and (sequential? current) (<= 0 segment) (< segment (count current)))
                (nth current segment)
                ::missing)

              (map? current)
              (map-value current segment)

              :else
              ::missing))
          value
          (parse-path-segments path)))

(defn- parse-json-text [text]
  (try
    (json/parse-string text)
    (catch Exception e
      (throw (ex-info (str "stdout was not valid JSON: " (.getMessage e)
                           "\nstdout head: " (stdout-head text)) {})))))

(defn- parse-edn-text [text]
  (try
    (edn/read-string text)
    (catch Exception e
      (throw (ex-info (str "stdout was not valid EDN: " (.getMessage e)
                           "\nstdout head: " (stdout-head text)) {})))))

(defn- parse-json-literal [text]
  (try
    (json/parse-string text)
    (catch Exception e
      (throw (ex-info (str "invalid JSON literal in step table: " text " - " (.getMessage e)) {})))))

(defn- parse-edn-literal [text]
  (try
    (edn/read-string text)
    (catch Exception _
      text)))

(defn- assert-stdout-contains [format-name parse-output parse-literal table]
  (let [stdout (or (current-output) "")
        value  (parse-output stdout)]
    (doseq [row (:rows table)]
      (let [[path expected-text] row
            expected             (parse-literal expected-text)
            actual               (value-at-path value path)]
        (when (= ::missing actual)
          (throw (ex-info (str "stdout " format-name " missing path: " path) {})))
        (when-not (= expected actual)
          (throw (ex-info (str "stdout " format-name " path " path
                               " expected " (pr-str expected)
                               " but was " (pr-str actual))
                          {})))))))

(defn- await-exit-code []
  (helper/await-condition #(some? (g/get :exit-code)))
  (g/get :exit-code))

(defn await-text [read-text pred]
  (let [text* (atom "")]
    (helper/await-condition
      (fn []
        (let [text (or (read-text) "")]
          (reset! text* text)
          (pred text))))
    @text*))

(defn- absolute-path [path]
  (if (str/starts-with? path "/")
    path
    (str (System/getProperty "user.dir") "/" path)))

(defn- lines-contain-in-order? [text patterns]
  (let [lines   (str/split-lines (or text ""))
        missing ::missing
        matched (reduce (fn [line-idx pattern]
                          (if (= missing line-idx)
                            missing
                            (or (first (keep-indexed (fn [idx line]
                                                       (when (and (> idx line-idx)
                                                                  (str/includes? line pattern))
                                                         idx))
                                                     lines))
                                missing)))
                        -1
                        patterns)]
    (not= missing matched)))

(defn extract-patterns [table]
  (map (fn [row]
         (-> (if (and (< 1 (count row)) (= "\\" (first row)))
               (str "\\| " (str/join "\\|" (rest row)))
               (first row))
             unescape-expected
             str/trim))
       (:rows table)))

;; region ----- Step bodies -----

#_{:clj-kondo/ignore [:type-mismatch]}
(defn parse-argv [args]
  (let [args (interpolate-args args)]
    (if (str/blank? args)
      []
      (loop [s (str/trim args) tokens []]
        (if (str/blank? s)
          tokens
          (cond
            (str/starts-with? s "'")
            (let [end (some-> (str/index-of s "'" 1) long)]
              (if end
                (recur (str/trim (subs s (inc end))) (conj tokens (subs s 1 end)))
                (conj tokens (subs s 1))))
            (str/starts-with? s "\"")
            (let [end (some-> (str/index-of s "\"" 1) long)]
              (if end
                (recur (str/trim (subs s (inc end))) (conj tokens (subs s 1 end)))
                (conj tokens (subs s 1))))
            :else
            (let [[tok rest-s] (str/split s #"\s+" 2)]
              (recur (or rest-s "") (conj tokens tok)))))))))

(defn- launcher-script []
  (or (g/get :isaac-launcher)
      (str (System/getProperty "user.dir") "/libexec/isaac")))

(defn isaac-launcher-run [args]
  (let [argv       (parse-argv args)
        root-dir   (g/get :root)
        script     (launcher-script)
        cmd        (into [script]
                       (concat (when root-dir ["--root" root-dir])
                               argv))
        {:keys [out err exit]} (apply process/shell
                                      {:dir      (System/getProperty "user.dir")
                                       :out      :string
                                       :err      :string
                                       :continue true}
                                      cmd)]
    (g/assoc! :output (or out ""))
    (g/assoc! :stderr (or err ""))
    (g/assoc! :exit-code (long exit))))

(defn isaac-run [args]
  (doseq [preflight @isaac-run-preflights*] (preflight))
  (let [argv             (parse-argv args)
        api-key-login?   (and (= "auth" (first argv))
                              (= "login" (second argv))
                              (some #(= "--api-key" %) argv))
        cmd-stub         (g/get :cmd-stub)
        run!             (fn []
                           (let [code (main/run argv)]
                             (g/assoc! :exit-code code)))
        run-with-stubs   (fn []
                           (if api-key-login?
                             (with-redefs [read-line (fn [] "sk-test-key")]
                               (run!))
                             (run!)))
        provider-configs (g/get :provider-configs)
        root-dir         (g/get :root)
        extra-opts       (let [base-opts (cond-> {}
                                           root-dir (assoc :root root-dir))
                               merged    (cond-> base-opts
                                           provider-configs (assoc :provider-configs provider-configs)
                                           (g/get :main-extra-opts) (merge (g/get :main-extra-opts)))]
                           merged)
        stdin-content    (g/get :stdin-content)
         run-final        (fn []
                            (let [run* (fn [run-opts]
                                         (binding [root/*user-home* (or (g/get :user-home) root/*user-home*)
                                                   shell/*sh*       (or (g/get :sh-fn) shell/*sh*)
                                                   shell/*os-name*  (or (g/get :os-name) shell/*os-name*)]
                                           (if (seq run-opts)
                                             (binding [main/*extra-opts* run-opts]
                                               (run-with-stubs))
                                             (run-with-stubs))))]
                             (if-let [mem-fs (g/get :mem-fs)]
                                (nexus/-with-nested-nexus {:fs mem-fs}
                                  ((apply-run-wrappers #(run* (assoc extra-opts :fs mem-fs)))))
                                ((apply-run-wrappers #(run* extra-opts))))))
         run-with-stdin   (fn []
                            (if stdin-content
                              (binding [*in* (java.io.BufferedReader. (java.io.StringReader. stdin-content))]
                                (run-final))
                              (run-final)))
         output-writer    (java.io.StringWriter.)
         error-writer     (java.io.StringWriter.)]
    (binding [*out* output-writer
              *err* error-writer]
      (if cmd-stub
        (with-redefs [shell/cmd-available? (fn [cmd] (get cmd-stub cmd false))]
          (run-with-stdin))
        (run-with-stdin)))
    (g/assoc! :output (str output-writer))
    (g/assoc! :stderr (str error-writer))
    (doseq [postflight @isaac-run-postflights*] (postflight))))

(defn isaac-run-background [args]
  (doseq [preflight @isaac-run-preflights*] (preflight))
  (let [argv          (parse-argv args)
        root-dir      (g/get :root)
        extra-opts    (cond-> {}
                        root-dir (assoc :root root-dir))
        mem-fs        (g/get :mem-fs)
        output-writer (java.io.StringWriter.)
        error-writer  (java.io.StringWriter.)]
    (g/assoc! :live-output-writer output-writer)
    (g/assoc! :live-error-writer error-writer)
    (future
      (let [run! (fn [run-opts]
                   (binding [*out* output-writer
                             *err* error-writer
                             root/*user-home* (or (g/get :user-home) root/*user-home*)]
                     (let [code ((apply-run-wrappers
                                   #(if (seq run-opts)
                                      (binding [main/*extra-opts* run-opts]
                                        (main/run argv))
                                      (main/run argv))))]
                       (g/assoc! :exit-code code))))]
        (if mem-fs
          (nexus/-with-nested-nexus {:fs mem-fs}
            (run! (assoc extra-opts :fs mem-fs)))
          (run! extra-opts))))
    (doseq [postflight @isaac-run-postflights*] (postflight))))

(defn user-home-directory [path]
  (let [home (if (str/starts-with? path "/")
               path
               (str (System/getProperty "user.dir") "/" path))
        dir  (io/file home)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (.delete file)))
    (.mkdirs dir)
    (g/assoc! :user-home home)))

(defn stdout-contains [expected]
  (let [expected (unescape-expected expected)
        output   (await-text current-output #(str/includes? % expected))]
    (g/should (str/includes? output expected))))

(defn stdout-eventually-contains [expected]
  (let [expected (unescape-expected expected)
        output   (await-text current-output #(str/includes? % expected))]
    (g/should (str/includes? output expected))))

(defn stderr-contains [expected]
  (let [expected (unescape-expected expected)
        stderr   (await-text current-stderr #(str/includes? % expected))]
    (g/should (str/includes? stderr expected))))

(defn stderr-does-not-contain [expected]
  (let [stderr   (current-stderr)
        expected (unescape-expected expected)]
    (g/should-not (str/includes? (or stderr "") expected))))

(defn stderr-matches [table]
  (let [stderr   (or (current-stderr) "")
        patterns (map (fn [row]
                        (-> (if (and (< 1 (count row)) (= "\\" (first row)))
                              (str "\\| " (str/join "\\|" (rest row)))
                              (first row))
                            unescape-expected
                            str/trim))
                       (:rows table))]
    (doseq [pattern patterns]
      (g/should (re-find (re-pattern pattern) stderr)))))

(defn stdout-lines-contain-in-order [table]
  (let [patterns (map #(-> (first %) unescape-expected str/trim) (:rows table))
        output   (await-text current-output #(lines-contain-in-order? % patterns))]
    (g/should (lines-contain-in-order? output patterns))))

(defn stdout-lines-match [table]
  (let [normalize (fn [lines] (mapv #(str/trim (or % "")) lines))
        expected  (normalize (map #(unescape-expected (get (zipmap (:headers table) %) "text")) (:rows table)))
        output    (await-text current-output #(= expected (normalize (str/split-lines (or % "")))))]
    (g/should= expected (normalize (str/split-lines (or output ""))))))

(defn stdout-has-at-least-lines [n]
  (let [output (or (current-output) "")
        n      (if (string? n) (parse-long n) n)]
    (g/should (<= n (count (str/split-lines output))))))

(defn stdout-matches [table]
  (let [output   (or (current-output) "")
        patterns (extract-patterns table)]
    (doseq [pattern patterns]
      (g/should (re-find (re-pattern pattern) output)))))

(defn stdout-json-contains [table]
  (assert-stdout-contains "JSON" parse-json-text parse-json-literal table)
  (g/should true))

(defn stdout-edn-contains [table]
  (assert-stdout-contains "EDN" parse-edn-text parse-edn-literal table)
  (g/should true))

(defn stdout-does-not-match [table]
  (let [output   (or (current-output) "")
        patterns (extract-patterns table)]
    (doseq [pattern patterns]
      (g/should-not (re-find (re-pattern pattern) output)))))

(defn stdout-does-not-contain [expected]
  (let [output   (current-output)
        expected (unescape-expected expected)]
    (g/should-not (str/includes? (or output "") expected))))

(defn exit-code-is [code]
  (let [code (if (string? code) (parse-long code) code)]
    (g/should= code (or (await-exit-code) (g/get :exit-code)))))

(defn command-available [cmd]
  (g/assoc! :cmd-stub {cmd true}))

(defn command-not-available [cmd]
  (g/assoc! :cmd-stub {cmd false}))

(defn stdin-is [doc-string]
  (g/assoc! :stdin-content (str/trim doc-string)))

(defn stdin-is-empty []
  (g/assoc! :stdin-content ""))

(defn clock-is-fixed-at [iso]
  (let [iso (unescape-expected iso)
        iso (if (and (str/starts-with? iso "\"") (str/ends-with? iso "\""))
              (subs iso 1 (dec (count iso)))
              iso)]
    (g/assoc! :current-time (java.time.Instant/parse iso))))

(defn isaac-root-contains-config [root doc-string]
  (let [abs-root    (absolute-path root)
        config-dir  (str abs-root "/config")
        config-file (str config-dir "/isaac.edn")
        mem-fs      (g/get :mem-fs)]
    (if mem-fs
      (nexus/-with-nested-nexus {:fs mem-fs}
        (fs/mkdirs mem-fs config-dir)
        (fs/spit   mem-fs config-file (str/trim doc-string)))
      (do (.mkdirs (io/file config-dir))
          (spit config-file (str/trim doc-string))))
    (g/assoc! :root abs-root)))

(defn isaac-root-has-no-config [root]
  (let [abs-root    (absolute-path root)
        config-dir  (str abs-root "/config")
        config-file (str config-dir "/isaac.edn")]
    (if-let [mem-fs (g/get :mem-fs)]
      (nexus/-with-nested-nexus {:fs mem-fs}
        (when (fs/exists? mem-fs config-file)
          (fs/delete mem-fs config-file))
        (fs/mkdirs mem-fs abs-root))
      (do
        (when (.exists (io/file config-file))
          (.delete (io/file config-file)))
        (.mkdirs (io/file abs-root))))
    (g/assoc! :root abs-root)))

(defn- strip-quotes [s]
  (if (and (str/starts-with? s "\"") (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn- isaac-file-path-for-assert [path]
  (let [root (absolute-path (or (g/get :root) "."))
        path (strip-quotes path)]
    (cond
      (str/starts-with? path "/") path
      (= path "isaac.edn")         (str root "/config/isaac.edn")
      (str/starts-with? path "config/") (str root "/" path)
      :else                        (str root "/" path))))

(defn isaac-file-edn-contains [path table]
  (let [full-path (isaac-file-path-for-assert path)
        text      (if-let [mem-fs (g/get :mem-fs)]
                    (nexus/-with-nested-nexus {:fs mem-fs}
                      (fs/slurp mem-fs full-path))
                    (slurp full-path))
        value     (parse-edn-text text)]
    (doseq [row (:rows table)]
      (let [[path expected-text] row
            expected             (parse-edn-literal expected-text)
            actual               (value-at-path value path)]
        (when (= ::missing actual)
          (throw (ex-info (str "isaac file EDN missing path: " path
                                " in " full-path) {})))
        (when-not (= expected actual)
          (throw (ex-info (str "isaac file EDN path " path
                               " expected " (pr-str expected)
                               " but was " (pr-str actual)
                               " in " full-path)
                          {})))))))

(defn isaac-file-contains [path content]
  (let [full-path (if (str/starts-with? path "/")
                    path
                    (str (g/get :root) "/" path))
        expected  (str/trim content)]
    (if-let [mem-fs (g/get :mem-fs)]
      (nexus/-with-nested-nexus {:fs mem-fs}
        (g/should= expected (fs/slurp mem-fs full-path)))
      (g/should= expected (slurp full-path)))))

;; endregion ^^^^^ Step bodies ^^^^^

;; region ----- Routing -----

(defwhen "isaac is run in the background with {args:string}" isaac.foundation.cli-steps/isaac-run-background
  "Runs 'isaac <args>' in a background thread. Binds a live StringWriter to
   *out* and stores it as :live-output-writer so 'the stdout eventually contains'
   can poll while the command is still running.")

(defwhen "the isaac launcher is run with {args:string}" isaac.foundation.cli-steps/isaac-launcher-run
  "Shells out to the packaged isaac launcher (libexec/isaac) so each run
   composes a fresh classpath from config :modules. Passes --root when a
   scenario root is in scope. Captures :output, :stderr, :exit-code.")

(defwhen "isaac is run with {args:string}" isaac.foundation.cli-steps/isaac-run
   "Runs 'isaac <args>' in-process (not a subprocess). Parses argv with
   quoted-token handling, binds *in*/*out*/*err* to capture streams,
   applies any cmd-stubs, propagates mem-fs if set, and routes through
   main/*extra-opts* when root / provider-configs are in scope. Captures
   :output (stdout), :stderr, :exit-code; registered postflights harvest
   any provider/LLM state for downstream assertions.")

(defgiven "the user home directory is {path:string}" isaac.foundation.cli-steps/user-home-directory
  "Deletes and recreates the given directory on the real filesystem,
   then binds it as *user-home* for the next 'isaac is run with'. Path
   may be absolute or relative (relative resolves under user.dir).")

(defthen "the stdout contains {expected:string}" isaac.foundation.cli-steps/stdout-contains
  "Polls up to 1s for captured stdout to contain the given substring.
   Reads :live-output-writer if set (async proxy runs), else :output.")

(defthen "the stdout eventually contains {expected:string}" isaac.foundation.cli-steps/stdout-eventually-contains)

(defthen "the stderr contains {expected:string}" isaac.foundation.cli-steps/stderr-contains)

(defthen "the stderr does not contain {expected:string}" isaac.foundation.cli-steps/stderr-does-not-contain)

(defthen "the stderr matches:" isaac.foundation.cli-steps/stderr-matches)

(defthen "the stdout lines contain in order:" isaac.foundation.cli-steps/stdout-lines-contain-in-order)

(defthen "the stdout lines match:" isaac.foundation.cli-steps/stdout-lines-match)

(defthen "the stdout has at least {int} lines" isaac.foundation.cli-steps/stdout-has-at-least-lines)

(defthen "the stdout matches:" isaac.foundation.cli-steps/stdout-matches
  "Each row's 'pattern' cell is compiled as a regex and searched across
   stdout. All rows must match somewhere (order not enforced). Since
   re-find succeeds on any match, multi-line shape isn't verified —
   pair with 'the stdout has at least N lines' when structure matters.")

(defthen "the stdout JSON contains:" isaac.foundation.cli-steps/stdout-json-contains)

(defthen "the stdout EDN contains:" isaac.foundation.cli-steps/stdout-edn-contains)

(defthen "the stdout does not match:" isaac.foundation.cli-steps/stdout-does-not-match
  "Each row's 'pattern' cell is compiled as a regex and asserts it is NOT found anywhere in stdout.")

(defthen "the stdout does not contain {expected:string}" isaac.foundation.cli-steps/stdout-does-not-contain)

(defthen "the exit code is {int}" isaac.foundation.cli-steps/exit-code-is
  "Polls up to 1s for :exit-code to be set (background 'isaac is run'
   futures may not have finished yet).")

(defgiven "the command {cmd:string} is available" isaac.foundation.cli-steps/command-available
  "Stubs isaac.shell/cmd-available? to return true for this command
   for the next 'isaac is run with'. Does not actually install anything —
   purely a test-time override. Only one stub at a time (replaces prior).")

(defgiven "the command {cmd:string} is not available" isaac.foundation.cli-steps/command-not-available
  "Stubs isaac.shell/cmd-available? to return false for this command
   for the next 'isaac is run with'. Pairs with 'command is available'.")

(defgiven "stdin is:" isaac.foundation.cli-steps/stdin-is
  "Buffers the heredoc content as stdin for the next 'isaac is run with'.
   Without this step, *in* is closed for the run.")

(defgiven "stdin is empty" isaac.foundation.cli-steps/stdin-is-empty)

(defgiven "the clock is fixed at {string}" isaac.foundation.cli-steps/clock-is-fixed-at
  "Pins the run clock (:current-time) for the next 'isaac is run with' so
   CLI scenarios can assert deterministic timestamps. A registered run-
   wrapper binds the concrete clock var during the run.")

(defgiven "Isaac root {root:string} contains config:" isaac.foundation.cli-steps/isaac-root-contains-config
  "Writes the heredoc content as <root>/config/isaac.edn. Differs
   from 'the isaac EDN file' steps, which write per-entity files under
   root. This is for the monolithic root config.")

(defgiven "Isaac root {root:string} has no config file" isaac.foundation.cli-steps/isaac-root-has-no-config)

(defthen #"the isaac file \"([^\"]+)\" contains:" isaac.foundation.cli-steps/isaac-file-contains
  "Asserts an exact-match on file content (trimmed). Path is root-
   relative. Pair with 'the isaac EDN file X exists with:' for the write
   side; 'contains:' is for read-side verification.")

(defthen #"the isaac file \"([^\"]+)\" EDN contains:" isaac.foundation.cli-steps/isaac-file-edn-contains
  "Assert-only structural EDN inspector for isaac files. Table rows are
   path | value, using the same dotted-path semantics as 'the stdout EDN
   contains:'.")
