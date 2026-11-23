
while true; do
	/root/qwentts.cpp/build/tts-server \
		--model /root/autodl-fs/qwen-talker-1.7b-customvoice-Q8_0.gguf \
		--codec /root/autodl-fs/qwen-tokenizer-12hz-Q8_0.gguf \
		--host 0.0.0.0 --port 9080
done
