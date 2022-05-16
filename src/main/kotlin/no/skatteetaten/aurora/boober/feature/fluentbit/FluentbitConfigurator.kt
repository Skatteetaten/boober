package no.skatteetaten.aurora.boober.feature.fluentbit

class FluentbitConfigurator {
    companion object {

        /**
         * Fluentbit parser config.
         * - timeParser is used for extracting the timestamp from the log and assigning it to the key <time> of the record
         * - multiline-log4j is a MULTILINE_PARSER that groups multiline logs into a single event.
         *   It uses the timestamp to recognize the first line of a log line and continues until it meets another timestamp
         */
        fun parserConf(): String = """
        |[PARSER]
        |   Name        timeParser
        |   Format      regex
        |   Regex       ^(?<timestamp>\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*Z) (.*)
        |   Time_Key    timestamp
        |   Time_Format %Y-%m-%dT%H:%M:%S,%L%z
        |
        |[MULTILINE_PARSER]
        |   name          multiline-log4j
        |   type          regex
        |   key_content   event
        |   flush_timeout 1000
        |   rule          "start_state"   "/^(\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*(Z|\+\d{4}))(.*)$/"  "cont"
        |   rule          "cont"          "/^(?!\d{4}-\d{1,2}-\d{1,2}T\d{2}:\d{2}:\d{2},\d*(Z|\+\d{4}))(.*)$/"  "cont"
        |
        |[MULTILINE_PARSER]
        |   name          multiline-evalevent-xml
        |   type          regex
        |   key_content   event
        |   flush_timeout 1000
        |   rule          "start_state" "/<EvaluationEvent.*/"       "cont"
        |   rule          "cont"        "/^(?!<EvaluationEvent).*$/" "cont"
        """.trimMargin()

        /**
         * Fluentbit config
         */
        fun generateFluentBitConfig(
            allConfiguredLoggers: List<LoggingConfig>,
            application: String,
            cluster: String,
            version: String,
            bufferSize: Int,
            retryLimit: Int?
        ): String {
            val logInputList = getLoggInputList(allConfiguredLoggers, bufferSize)
            val applicationSplunkOutputs = allConfiguredLoggers.joinToString("\n\n") {
                it.run {
                    generateSplunkOutput(
                        matcherTag = "$name-$sourceType",
                        index = index,
                        sourceType = sourceType,
                        retryLimit = retryLimit
                    )
                }
            }

            val fluentbitIndex = allConfiguredLoggers.find { it.name == logApplication }?.index
                ?: throw IllegalArgumentException("Application logger has not been provided")

            val fluentbitSplunkOutput =
                generateSplunkOutput(matcherTag = "fluentbit", index = fluentbitIndex, sourceType = "fluentbit")

            return listOfNotNull(
                fluentbitService,
                logInputList,
                fluentbitLogInputAndFilter,
                timeParserFilter,
                multilineLog4jFilter,
                multilineEvalXmlFilter,
                getModifyFilter(application, cluster, version),
                applicationSplunkOutputs,
                fluentbitSplunkOutput
            ).joinToString("\n\n")
                .replace(
                    "$ {",
                    "\${"
                ) // Fluentbit uses $(variable) but so does kotlin multiline string, so space between $ and ( is used in config and must be replaced here.
        }

        private val fluentbitService: String = """
        |[SERVICE]
        |   Flush        1
        |   Daemon       Off
        |   Log_Level    info
        |   Log_File     /u01/logs/fluentbit
        |   Parsers_File $parserMountPath/$parsersFileName
        """.trimMargin()

        // Fluentibt input for each logging config
        private fun getLoggInputList(
            loggerIndexes: List<LoggingConfig>,
            bufferSize: Int
        ) = loggerIndexes.joinToString("\n\n") { log ->
            """
            |[INPUT]
            |   Name            tail
            |   Path            /u01/logs/${log.filePattern}
            |   Path_Key        source
            |   Exclude_Path    ${log.excludePattern}
            |   Read_From_Head  true
            |   Tag             ${log.name}-${log.sourceType}
            |   DB              /u01/logs/${log.name}.db
            |   Buffer_Max_Size 512k
            |   Skip_Long_Lines On
            |   Mem_Buf_Limit   ${bufferSize}MB
            |   Rotate_Wait     10
            |   Key             event
            """.trimMargin()
        }

        // Input for the log file produced by fluentbit
        // Filters it to stdout
        private val fluentbitLogInputAndFilter: String = """
        |[INPUT]
        |   Name             tail
        |   Path             /u01/logs/fluentbit
        |   Path_Key         source
        |   Tag              fluentbit
        |   Refresh_Interval 5
        |   Read_from_Head   true
        |   Key              event
        |
        |[FILTER]
        |   Name             stdout
        |   Match            fluentbit
        """.trimMargin()

        // Parser filter to assign it to application tag records
        private val timeParserFilter = """
        |[FILTER]
        |   Name parser
        |   Match *-log4j
        |   Key_Name event
        |   Parser timeParser
        |   Preserve_Key On
        |   Reserve_Data On
        """.trimMargin()

        // Multiline filter to assign the multiline_parser to application tag records
        private val multilineLog4jFilter = """
        |[FILTER]
        |   name multiline
        |   match *-log4j
        |   multiline.key_content event
        |   multiline.parser multiline-log4j
        """.trimMargin()

        // Multiline filter to assign the XML multiline parser to application tag records
        private val multilineEvalXmlFilter = """
        |[FILTER]
        |   name multiline
        |   match evalevent_xml
        |   multiline.key_content event
        |   multiline.parser multiline-evalevent-xml
        |
        |[FILTER]
        |   name multiline
        |   match ats:eval:xml
        |   multiline.key_content event
        |   multiline.parser multiline-evalevent-xml
        """.trimMargin()

        // Fluentbit filter for adding splunk fields for application, cluster, environment, host and nodetype to the record
        private fun getModifyFilter(application: String, cluster: String, version: String) = """
        |[FILTER]
        |   Name  modify
        |   Match *
        |   Add   host $ {POD_NAME}
        |   Add   environment $ {POD_NAMESPACE}
        |   Add   version $version
        |   Add   nodetype openshift
        |   Add   name $application
        |   Add   cluster $cluster
        """.trimMargin()

        /**
         * Splunk output for a given tag, index and sourectype.
         * The output extracts fields by using event_field and record accessor. Fields are added to the record by a previous filter
         * Event_key extracts the "event" key from the record and uses it to build up the HEC payload
         */
        private fun generateSplunkOutput(
            matcherTag: String,
            index: String,
            sourceType: String,
            retryLimit: Int? = null
        ): String {
            val retryConfigOrEmpty = retryLimit?.let {
                """
                |
                |   Retry_Limit $it
                """.trimMargin()
            } ?: ""

            return """
            |[OUTPUT]
            |   Name                       splunk
            |   Match                      $matcherTag 
            |   Host                       $ {SPLUNK_HOST}
            |   Port                       $ {SPLUNK_PORT}
            |   Splunk_token               $ {HEC_TOKEN}
            |   TLS                        On
            |   TLS.Verify                 Off
            |   event_index                $index
            |   event_sourcetype           $sourceType
            |   event_host                 $ {POD_NAME}
            |   event_source               ${'$'}source
            |   event_field                application ${'$'}name
            |   event_field                cluster ${'$'}cluster
            |   event_field                environment ${'$'}environment
            |   event_field                nodetype ${'$'}nodetype
            |   event_field                version ${'$'}version
            |   event_key                  ${'$'}event
            |   net.keepalive_idle_timeout 10
            """.trimMargin() + retryConfigOrEmpty
        }
    }
}
