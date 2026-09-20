(ns isaac.config.watch-spec
  (:require
    [isaac.config.paths :as paths]
    [isaac.config.watch :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(describe "config hot-reload ownership (isaac-1pi2)"

  (context "hot-reload?"

    (it "is on when the host says nothing"
      ;; The resolver always claimed this default; the gate that started the
      ;; watcher read the raw config and never applied it, so every host that
      ;; never set the key silently never reloaded.
      (should= true (sut/hot-reload? {}))
      (should= true (sut/hot-reload? {:crew {} :modules {}})))

    (it "is off only when the host turns it off"
      (should= false (sut/hot-reload? {:hot-reload false})))

    (it "is on when the host turns it on"
      (should= true (sut/hot-reload? {:hot-reload true})))

    (it "still honours the retired [:server :hot-reload], and says so"
      (let [entries (atom [])]
        (with-redefs [log/log* (fn [level event & _] (swap! entries conj [level event]))]
          (should= false (sut/hot-reload? {:server {:hot-reload false}}))
          (should= true (sut/hot-reload? {:server {:hot-reload true}})))
        (should= [[:warn :config.watch/retired-key] [:warn :config.watch/retired-key]] @entries)))

    (it "prefers the current key over the retired one"
      (should= true (sut/hot-reload? {:hot-reload true :server {:hot-reload false}}))))

  (context "start!"

    (it "says why it did not start, rather than going quiet"
      (let [entries (atom [])]
        (with-redefs [log/log* (fn [level event & kvs] (swap! entries conj [level event (apply hash-map kvs)]))]
          (should-be-nil (sut/start! {:config {} :root nil}))
          (should-be-nil (sut/start! {:config {:hot-reload false} :root "/tmp/x"})))
        (should= [:no-root :configured-off] (mapv #(:reason (nth % 2)) @entries))))

    (it "watches the config root when the host says nothing"
      (let [watched (atom nil)]
        (with-redefs [isaac.config.runtime/watch-service-source (fn [root] (reset! watched root) {:fake true})
                      isaac.config.runtime/start! identity
                      isaac.config.runtime/stop! identity
                      isaac.config.watch/registries (constantly [])]
          (let [handle (sut/start! {:config {} :root "/test/root" :fs nil :host {}})]
            (should= "/test/root" @watched)
            (should= {:fake true} (:source handle))
            (sut/stop! handle))))))

  (context "what counts as a config file"

    (it "includes every kind under config/, not just isaac.edn"
      ;; isaac-1pi2 surfaced through a crew file created after boot.
      (should (paths/config-file? "isaac.edn"))
      (should (paths/config-file? "crew/qwen.edn"))
      (should (paths/config-file? "models/qwen3-coder-next.edn"))
      (should (paths/config-file? "providers/ollama-nightbird.edn"))
      (should (paths/config-file? "crew/qwen.md")))

    (it "ignores what is not config"
      (should-not (paths/config-file? "notes.txt"))
      (should-not (paths/config-file? "sessions/work-1/current.ednl"))))
  )
