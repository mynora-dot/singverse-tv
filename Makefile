.DEFAULT_GOAL := help
.PHONY: help check build release test release-check
help:
	@printf '%s\n' 'make check: 单元测试与 lint' 'make build: 检查并构建调试 APK' 'make release: 使用环境中的正式签名构建并核验发布资产'
check:
	./gradlew testDebugUnitTest lintDebug
build:
	./gradlew testDebugUnitTest assembleDebug lintDebug
release: release-check
	./gradlew -PsingverseSignRelease=true prepareTvRelease lintRelease
test:
	./gradlew testDebugUnitTest

release-check:
	python3 -m unittest discover -s scripts/tests -v
