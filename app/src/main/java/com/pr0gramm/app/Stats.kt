package com.pr0gramm.app

import com.pr0gramm.app.util.debugOnly
import com.pr0gramm.app.util.doInBackground
import com.timgroup.statsd.NoOpStatsDClient
import com.timgroup.statsd.NonBlockingStatsDClient
import com.timgroup.statsd.StatsDClient

private class LoggingStatsDClient : StatsDClient by NoOpStatsDClient() {
    private val logger = Logger("LoggingStatsdClient")

    override fun recordGaugeValue(aspect: String, value: Double, vararg tags: String?) {
        logger.debug { "Stats.recordGaugeValue($aspect, $value)" }
    }

    override fun recordGaugeValue(aspect: String, value: Long, vararg tags: String?) {
        logger.debug { "Stats.recordGaugeValue($aspect, $value)" }
    }

    override fun decrementCounter(aspect: String, vararg tags: String?) {
        logger.debug { "Stats.decrementCounter($aspect)" }
    }

    override fun recordExecutionTime(aspect: String, timeInMs: Long, vararg tags: String?) {
        logger.debug { "Stats.recordExecutionTime($aspect, $timeInMs)" }
    }

    override fun time(aspect: String, value: Long, vararg tags: String?) {
        logger.debug { "Stats.time($aspect, $value)" }
    }

    override fun histogram(aspect: String, value: Double, vararg tags: String?) {
        logger.debug { "Stats.histogram($aspect, $value)" }
    }

    override fun histogram(aspect: String, value: Long, vararg tags: String?) {
        logger.debug { "Stats.histogram($aspect, $value)" }
    }

    override fun count(aspect: String, delta: Long, vararg tags: String?) {
        logger.debug { "Stats.count($aspect, $delta)" }
    }

    override fun recordSetValue(aspect: String, value: String?, vararg tags: String?) {
        logger.debug { "Stats.recordSetValue($aspect, $value)" }
    }

    override fun recordHistogramValue(aspect: String, value: Double, vararg tags: String?) {
        logger.debug { "Stats.recordHistogramValue($aspect, $value)" }
    }

    override fun recordHistogramValue(aspect: String, value: Long, vararg tags: String?) {
        logger.debug { "Stats.recordHistogramValue($aspect, $value)" }
    }

    override fun incrementCounter(aspect: String, vararg tags: String?) {
        logger.debug { "Stats.incrementCounter($aspect)" }
    }

    override fun gauge(aspect: String, value: Double, vararg tags: String?) {
        logger.debug { "Stats.gauge($aspect, $value)" }
    }

    override fun gauge(aspect: String, value: Long, vararg tags: String?) {
        logger.debug { "Stats.gauge($aspect, $value)" }
    }

    override fun increment(aspect: String, vararg tags: String?) {
        logger.debug { "Stats.increment($aspect)" }
    }

    override fun decrement(aspect: String, vararg tags: String?) {
        logger.debug { "Stats.decrement($aspect)" }
    }
}
