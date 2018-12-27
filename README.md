# clj-honeycomb

A library for sending events to [Honeycomb.io](https://www.honeycomb.io/),
wrapping [libhoney-java 1.0.2](https://github.com/honeycombio/libhoney-java).

## Usage

Include the following in your `project.clj`:

```clojure
; This is libhoney-java 1.0.2 build number 1 on CircleCI
; See https://circleci.com/gh/conormcd/clj-honeycomb for the latest build
[conormcd/clj-honeycomb "1.0.2.1"]
```

Then initialize the library somewhere:

```clojure
(require '[clj-honeycomb.core :as honeycomb])

(honeycomb/init {:data-set "The name of your Honeycomb data set"
                 :write-key "Your Honeycomb team write key (API key)"})
```

Now send an event:

```clojure
(honeycomb/send {:foo "bar"})
```

You can also wrap a chunk of code and trigger an event when the code
completes. It also times the code and instruments any exceptions thrown from
the body.

```clojure
(honeycomb/with-event {:foo "foo"} {}
  (honeycomb/add-to-event {:bar "bar"})
  ... code ...
  (honeycomb/add-to-event :baz "baz")
  ... code ...)

; The above sends an event like this:
; {"foo" "foo"
;  "bar" "bar"
;  "baz" "baz"
;  "elapsed-ms" 1234.567}
```

### Global and dynamic fields

You can add fields to every event by setting them on the client at
initialization time. You can delay computation of the value of those fields
until the time the event is sent by using atoms, delays or promises.

```clojure
(def my-dynamic-field (atom 1))

(honeycomb/init {:global-fields {:global-dynamic my-dynamic-field
                                 :global-static "static"}})
(honeycomb/send {:event-specific "event"})
(swap! my-dynamic-field inc)
(honeycomb/send {:event-specific "event"})

; The above results in the following two events being sent:
; {"event-specific" "event" "global-dynamic" 1 "global-static" "static"}
; {"event-specific" "event" "global-dynamic" 2 "global-static" "static"}
```

You can make more sophisticated dynamic fields by implementing a
[ValueSupplier](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/ValueSupplier.html).
There is a convenience function `clj-honeycomb.fields/->ValueSupplier` to
transform a Clojure function into one.

```clojure
(honeycomb/init {:global-fields {:vs (->ValueSupplier rand-int 100)}})
(honeycomb/send {:event "event"})

; The above produces:
; {"event" "event" "vs" 15}
```

### Sampling

Honeycomb provides [useful
information](https://docs.honeycomb.io/getting-data-in/sampling/) on why and
when to sample. Sample rates can be set globally at initialization time with
the `:sample-rate` key or individually on each `send` by passing a
`sample-rate` in the options map which is the third argument to `send`. If you
implement your own sampling, you must pass `{:sample-rate ... :pre-sampled
true}` to each call to `send`.

### Monitoring

The libhoney-java library sends events to Honeycomb asynchronously on a
background thread. It also batches events. To monitor the progress of the
sending of events you can add a
[ResponseObserver](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/ResponseObserver.html)
to the client. The easiest way to do that is by adding one or more functions
to a `:response-observer` map in the client options passed to either `init` or
`client`.

```clojure
(honeycomb/init {:data-set "data-set"
                 :write-key "write-key"
                 :response-observer {:on-client-rejected
                                     (fn [client-rejected]
                                       ...)
                                     :on-server-accepted
                                     (fn [server-accepted]
                                       ...)
                                     :on-server-rejected
                                     (fn [server-rejected]
                                       ...)
                                     :on-unknown
                                     (fn [unknown]
                                       ...)}})
```

You may omit any of the functions in the `:response-observer` map with no ill
effects. Each of the functions takes a single argument and the types of the
arguments are as follows:

- :on-client-rejected - [ClientRejected](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/responses/ClientRejected.html)
- :on-server-accepted - [ServerAccepted](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/responses/ServerAccepted.html)
- :on-server-rejected - [ServerRejected](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/responses/ServerRejected.html)
- :on-unkonwn - [Unknown](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/responses/Unknown.html)

### Managing client state

If you would like to avoid using this library in a stateful manner you can
avoid calling `clj-honeycomb.core/init` and accomplish everything with
`clj-honeycomb.core/client` and `clj-honeycomb.core/send`. The former is used
to create a [HoneyClient](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/HoneyClient.html)
which can then be passed as the first argument to `send` in order to send
events. The `with-event` macro will throw if you attempt to use it without
calling `init` first. You are responsible for calling `.close` on the client
before disposing of it. It's recommended that you use `with-open` or some
state management system like component or mount.

### Testing

If you're testing code that uses the implicit client created with `init` then
you can use `clj-honeycomb.testing-utils/validate-events` to make assertions
about the events sent by some code. This also prevents events from being sent
to Honeycomb. The events passed to the second function is a vector of
[ResolvedEvent](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/eventdata/ResolvedEvent.html).

```clojure
(require '[clj-honeycomb.testing-utils :refer (validate-events)])

(validate-events
 (fn []
   ... code that emits events ...
   )
 (fn [events errors]
   ... events contains all the events that would have been sent ...
   ... errors contains any errors emitted by libhoney-java ...))
```

You can also create a fake `HoneyClient` which will record all the events sent
to it.

```clojure
(require '[clj-honeycomb.core :as honeycomb])
(require '[clj-honeycomb.testing-utils :refer (recording-client)])

(let [events (atom [])]
  (with-open [client (recording-client events {})]
    (honeycomb/send client {:foo "bar"}))
  ... events now contains the ResolvedEvent ...)
```

## License

Copyright 2018-2019 Conor McDermottroe

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this software except in compliance with the License. You may obtain a copy
of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.
