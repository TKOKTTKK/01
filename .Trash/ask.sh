#!/bin/sh
API_KEY="sk-feb82e3b987f6a709f23798b38bd442b0ebf2050d1a72e87cf6d0c61d6ab5ed1"
MODEL="claude-sonnet-5"
URL="https://lyozc.com/v1/chat/completions"
read -p "你想问什么: " PROMPT
curl -s -X POST "$URL" -H "Content-Type: application/json" -H "Authorization: Bearer $API_KEY" -d "{\"model\": \"$MODEL\", \"messages\": [{\"role\": \"user\", \"content\": \"$PROMPT\"}]}"
