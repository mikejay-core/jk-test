#!/usr/bin/env groovy
@Library('utils')
import com.core.libs.utils


def call(env) {
    bashScriptReturn "./gradlew clean check jacocoTestReportUnit"
    return bashScriptReturn "git log -n 1 --pretty=format:%s ${env.GIT_COMMIT}"
}