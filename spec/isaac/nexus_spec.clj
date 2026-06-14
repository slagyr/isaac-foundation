(ns isaac.nexus-spec
  (:require
    [isaac.nexus :as sut]
    [speclj.core :refer :all]))

(defn- with-fresh-system [f]
  (sut/-with-nexus {}
    (f)))

(describe "isaac.nexus"

  (around [it]
    (with-fresh-system it))

  ;; region ----- register! / get round-trip -----

  (describe "install! and current"

    (it "installs and returns the current runtime"
      (let [runtime {:root "/tmp/test"
                     :config    (atom {:crew {}})}]
        (sut/install! runtime)
        (should= runtime (sut/necho))
        (should= "/tmp/test" (sut/get :root)))))

  (describe "register! and get"

    (it "stores and retrieves a flat value by path"
      (sut/register! [:root] "/tmp/test")
      (should= "/tmp/test" (sut/get :root)))

    (it "returns nil for an unregistered key"
      (should-be-nil (sut/get :root)))

    (it "overwrites a previous value for the same path"
      (sut/register! [:root] "/tmp/first")
      (sut/register! [:root] "/tmp/second")
      (should= "/tmp/second" (sut/get :root)))

    (it "stores values of any type"
      (let [a (atom {})]
        (sut/register! [:tool-registry] a)
        (should= a (sut/get :tool-registry)))))

  ;; endregion ^^^^^ register! / get round-trip ^^^^^

  ;; region ----- get-in -----

  (describe "get-in"

    (it "returns a flat value by single-element path"
      (sut/register! [:root] "/tmp/test")
      (should= "/tmp/test" (sut/get-in [:root])))

    (it "returns a nested value by multi-element path"
      (sut/register! [:sessions :store] ::store)
      (should= ::store (sut/get-in [:sessions :store])))

    (it "returns nil for a missing path"
      (should-be-nil (sut/get-in [:sessions :store])))

    (it "does not disturb sibling keys when registering at a path"
      (sut/register! [:sessions :store] ::store)
      (sut/register! [:sessions :naming-strategy] ::strategy)
      (should= ::store (sut/get-in [:sessions :store]))
      (should= ::strategy (sut/get-in [:sessions :naming-strategy]))))

  ;; endregion ^^^^^ get-in ^^^^^

  ;; region ----- registered? -----

  (describe "registered?"

    (it "returns false before registration"
      (should-not (sut/registered? [:root])))

    (it "returns true after registration"
      (sut/register! [:root] "/tmp/test")
      (should (sut/registered? [:root])))

    (it "returns false for an unrelated key"
      (sut/register! [:root] "/tmp/test")
      (should-not (sut/registered? [:server])))

    (it "returns true for a nested path"
      (sut/register! [:sessions :store] ::store)
      (should (sut/registered? [:sessions :store])))

    (it "returns false for an absent nested key"
      (sut/register! [:sessions :store] ::store)
      (should-not (sut/registered? [:sessions :naming-strategy]))))

  ;; endregion ^^^^^ registered? ^^^^^

  ;; region ----- -with-nexus isolation -----

  (describe "-with-nexus"

    (it "provides an empty system when initialized with {}"
      (sut/-with-nexus {}
        (should-be-nil (sut/get :root))))

    (it "provides an pre-populated system"
      (sut/-with-nexus {:root "/preset"}
        (should= "/preset" (sut/get :root))))

    (it "isolates mutations from the outer scope"
      (sut/register! [:root] "/outer")
      (sut/-with-nexus {}
        (sut/register! [:root] "/inner"))
      (should= "/outer" (sut/get :root)))

    (it "inner scope does not see outer registrations"
      (sut/register! [:root] "/outer")
      (sut/-with-nexus {}
        (should-be-nil (sut/get :root))))

    (it "is visible to new threads created inside the scope"
      (let [seen (promise)]
        (sut/-with-nexus {:root "/thread-visible"}
          (.start (Thread. #(deliver seen (sut/get :root))))
          (should= "/thread-visible" (deref seen 1000 ::timeout))))))

  (describe "bound-runtime-fn"

    (it "captures the current runtime for later thread execution"
      (let [captured (sut/-with-nexus {:root "/captured"}
                       (sut/bound-runtime-fn (fn [] (sut/get :root))))
            seen     (promise)]
        (sut/reset!)
        (.start (Thread. #(deliver seen (captured))))
        (should= "/captured" (deref seen 1000 ::timeout)))))

  ;; endregion ^^^^^ -with-nexus isolation ^^^^^

  ;; region ----- init! -----

  (describe "init!"

    (it "registers default atoms for config and tool-registry"
      (sut/init!)
      (should (instance? clojure.lang.Atom (sut/get :config)))
      (should (instance? clojure.lang.Atom (sut/get :tool-registry))))

    (it "accepts explicit atom overrides"
      (let [cfg*   (atom {:crew {}})
            tools* (atom {"read" {:name "read"}})]
        (sut/init! {:config cfg* :tool-registry tools*})
        (should= cfg* (sut/get :config))
        (should= tools* (sut/get :tool-registry)))))

  ;; endregion ^^^^^ init! ^^^^^

  ;; region ----- schema structure -----

  (describe "schema"

    (it "is a map with :name :nexus"
      (should= :nexus (:name sut/schema)))

    (it "documents foundation-reserved slots only"
      (should= #{:fs :config :module-index :scheduler}
               (set (keys (:schema sut/schema)))))

    (it "omits platform-wide slots from foundation documentation"
      (let [ks (set (keys (:schema sut/schema)))]
        (should-not (contains? ks :server))
        (should-not (contains? ks :sessions))
        (should-not (contains? ks :tool-registry))
        (should-not (contains? ks :slash-registry))
        (should-not (contains? ks :comm-registry))
        (should-not (contains? ks :provider-registry)))))

  ;; endregion ^^^^^ schema structure ^^^^^

  )
