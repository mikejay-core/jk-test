package com.core


class Utils implements Serializable {
    def steps

    Utils(steps) {this.steps = steps}

    def bashScript(script) {
        steps.sh "echo IN BUILD SCRIPT"
        steps.sh "${script}"
    }   

    def bashScriptReturn(script) {
        return steps.sh(returnStdout: true, script:"${script}")
    }
}

