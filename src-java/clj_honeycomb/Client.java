package clj_honeycomb;

import clojure.lang.Fn;

import io.honeycomb.libhoney.HoneyClient;
import io.honeycomb.libhoney.Options;
import io.honeycomb.libhoney.TransportOptions;
import io.honeycomb.libhoney.transport.Transport;

/**
 * An extension of HoneyClient to add support for an event pre-processor
 * function.
 */
public class Client extends HoneyClient {
    private Fn eventPreProcessor;

    /** {@inheritDoc} */
    public Client(Options o) {
        super(o);
    }

    /** {@inheritDoc} */
    public Client(Options o, TransportOptions to) {
        super(o, to);
    }

    /** {@inheritDoc} */
    public Client(Options o, Transport t) {
        super(o, t);
    }

    /**
     * Add an event pre-processor function. See the code in clj-honeycomb.core
     * for the signature and use of this function.
     *
     * @param epp The Clojure function to add.
     */
    public void setEventPreProcessor(Fn epp) {
        eventPreProcessor = epp;
    }

    /**
     * Get the event pre-processor function, if any.
     *
     * @return The Clojure function to use as an event pre-processor.
     */
    public Fn getEventPreProcessor() {
        return eventPreProcessor;
    }
}
