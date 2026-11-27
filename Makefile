.DEFAULT_GOAL := help
.PHONY: help check build release
help:
	@printf '%s\n' 'make check: lint' 'make build: 检查并构建调试 APK' 'make release: 使用环境中的正式签名构建并核验发布资产'
check:
	./gradlew lintDebug
build:
	./gradlew assembleDebug lintDebug
release:
	./gradlew -PsingverseSignRelease=true prepareTvRelease lintRelease
