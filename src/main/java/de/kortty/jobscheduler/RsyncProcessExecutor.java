package de.kortty.jobscheduler;

import java.util.List;
import java.util.Map;

interface RsyncProcessExecutor {
    RsyncProcessResult execute(List<String> command, Map<String, String> environment) throws Exception;
}
