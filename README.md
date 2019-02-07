# clj-honeycomb

A library for sending events to [Honeycomb.io](https://www.honeycomb.io/),
wrapping [libhoney-java 1.0.6](https://github.com/honeycombio/libhoney-java).

[![Clojars Project](https://img.shields.io/clojars/v/conormcd/clj-honeycomb.svg)](https://clojars.org/conormcd/clj-honeycomb)
[![CircleCI](https://circleci.com/gh/conormcd/clj-honeycomb.svg?style=svg)](https://circleci.com/gh/conormcd/clj-honeycomb)
[![codecov](https://codecov.io/gh/conormcd/clj-honeycomb/branch/master/graph/badge.svg)](https://codecov.io/gh/conormcd/clj-honeycomb)

- [Usage](#usage)
  - [Global and dynamic fields](#global-and-dynamic-fields)
  - [Sampling](#sampling)
  - [Pre- and Post-processing events](#pre--and-post-processing-events)
  - [Middleware](#middleware)
  - [Monitoring](#monitoring)
  - [Managing client state](#managing-client-state)
  - [Testing](#testing)
- [API Documentation](#api-documentation)
- [License](#license)

## Usage

Include the following in your `project.clj`:

```clojure
; This is libhoney-java 1.0.6 build number 1 on CircleCI
; The most recent version can be found via the Clojars badge above
[conormcd/clj-honeycomb "1.0.6.1"]
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

### Pre- and Post-processing events

Events can be manipulated before they're sampled (with a clj-honeycomb
pre-processor function) and after they're sampled (with a libhoney-java
[EventPostProcessor](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/EventPostProcessor.html)).

To pre-process an event before it's handed off to libhoney-java, add a
function like `(fn [event-data options] ...)` to the `:event-pre-processor`
optional argument to `init` which returns a tuple `[event-data options]`. The
arguments and return value are identical to the arguments to
`clj-honeycomb.core/send` and may be manipulated in any way so long as the
returned value is a valid set of arguments for `send`.

Example:

```clojure
(defn- event-pre-processor
  "Add an extra field to the event data which is a count of the number of
   fields being sent."
  [event-data options]
  [(merge event-data
          {:num-items (inc (count event-data))})
   options])

(honeycomb/init {...
                 :event-pre-processor event-pre-processor
                 ...})
```

To post-process an event, add an
[EventPostProcessor](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/EventPostProcessor.html)
to the `:event-post-processor` optional argument to `init`. The `process`
method on that object will be called with a single, mutable
[EventData](https://honeycombio.github.io/libhoney-java/io/honeycomb/libhoney/eventdata/EventData.html)
object. This is called after sampling has taken place, so it will only be run
on events which will be sent to Honeycomb.io. See the documentation for the
EventPostProcessor class to understand the constraints on modifying the event.

### Middleware

#### Ring

You can turn every request served by a Ring-compatible HTTP server into a
Honeycomb event with `clj-honeycomb.middleware.ring/with-honeycomb-event`. By
default the event will contain every item from the request map except for
`:body` and from the response it will contain the status and all of the
headers. You can customize the fields added to the event by passing an options
map to the middleware.

The following defines a custom honeycomb middleware that extracts only some
of the request and response data but adds some static and dynamic fields.

```clojure
(def count-of-thingers
  "An atom keeping track of the count of something, to demonstrate dynamic
   fields. This will be dereferenced whenever the event fires."
  (atom 0))

(def my-custom-honeycomb-middleware
  (partial with-honeycomb-event
           {:honeycomb-event-data {:static-field "sent with every event"
                                   :num-thingers count-of-thingers}
            :extract-request-fields (fn [req]
                                      {:num-headers (count (:headers req))})
            :extract-response-fields (fn [res]
                                       {:status (:status res)})}))

; This will produce events that look like this:
; {"elapsed-ms" 83.932
;  "num-headers" 12
;  "num-thingers" 3
;  "static-field" "sent with every event"
;  "status" 404}
```

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

## API Documentation

Automatically generated API documentation is uploaded to GitHub Pages on every
release. It can be viewed here:

https://conormcd.github.io/clj-honeycomb/

Since this library wraps `libhoney-java` it may also be useful to refer to the
API documentation for that from time to time:

https://honeycombio.github.io/libhoney-java/index.html?overview-summary.html

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
