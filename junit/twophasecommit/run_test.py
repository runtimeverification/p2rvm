#!/usr/bin/env python

import os
import os.path
import subprocess
import sys

if __name__ == "__main__":
    sys.path.append(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import tools

def runTwoPhaseCommit():
    tools.progress("Running the twophasecommit tests...")
    tools.runNoError(["mvn", "clean", "test", "-Dtest=twophasecommit.TwoPhaseCommitTest"])
    tools.runNoError(["mvn", "clean"])

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    tools.runInDirectory(script_dir, runTwoPhaseCommit)

if __name__ == "__main__":
    main()
