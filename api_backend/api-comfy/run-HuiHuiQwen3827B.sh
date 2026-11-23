


/root/llama.cpp/build/bin/llama-server \
  -m /root/autodl-tmp/Huihui-Qwen3.8-27B-Swift/Huihui-Qwen3.8-27B-abliterated-Swift-1.5-GSQ-RCO-IQ3_S-mtp.gguf \
  -mm /root/autodl-tmp/Huihui-Qwen3.8-27B-Swift/mmproj-model-bf16.gguf \
  --jinja -fa on -ngl 99 -c 56144 \
  --temp 1.0 --top-p 0.95 --top-k 20 --min-p 0.0 \
  --presence-penalty 0.0 --repeat-penalty 1.0 --port 6008
