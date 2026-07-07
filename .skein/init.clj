(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))

(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
