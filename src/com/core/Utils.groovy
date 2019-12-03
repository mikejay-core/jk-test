package com.core


class Utils implements Serializable {
    def steps

    Utils(steps) {this.steps = steps}

    def bashScript(script) {
        sh("echo IN BUILD SCRIPT")
        sh "${script}"
    }   

    def bashScriptReturn(script) {
        return sh(returnStdout: true, script:"${script}")
    }
}

