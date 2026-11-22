// Telegram-Bibliothek (GramJS) für den Browser bündeln -> ../gram.js
// Bauen: cd telegram-bundle && npm install && node build.js
const { TelegramClient, Api } = require('telegram');
const { StringSession } = require('telegram/sessions');
const { computeCheck } = require('telegram/Password');
const qrcode = require('qrcode-generator');
window.FoxTG = { TelegramClient, Api, StringSession, computeCheck, qrcode };
