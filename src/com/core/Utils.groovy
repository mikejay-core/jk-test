package com.core


def bashScript(script) {
    sh "echo IN BUILD SCRIPT"
    sh "${script}"
}   

def static String bashScriptReturn(script) {
    return sh(returnStdout: true, script:"${script}")
}

