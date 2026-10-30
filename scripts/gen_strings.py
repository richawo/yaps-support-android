#!/usr/bin/env python3
"""Writes yaps-support's strings.xml for every supported locale. Usage: gen_strings.py <res dir>"""
import sys, os
from xml.sax.saxutils import escape

KEYS = """title welcome_title welcome_text composer_hint send attach attach_title attach_subtitle attach_photos
attach_camera attach_file files_count remove_attachment close status_sending status_failed sent_at read_at retry
delete typing typing_generic connection_offline connection_connecting connection_connected connection_paused blocked
verification_required unavailable load_earlier latest new_reply card_offer card_review offer_unavailable review_button
offer_button too_long image_unreadable file_too_large file_unsupported attachment_limit photo open_photo close_photo
uploading team you message_from notification_channel notification_channel_description""".split()

L = {}

L[""] = ["Support", "How can we help?", "Send us a message and we’ll reply right here.", "Message", "Send",
 "Add attachment", "Attach", "Up to 5 photos or files per message", "Photos", "Camera", "File", "%1$d of %2$d files",
 "Remove attachment", "Close", "Sending…", "Not delivered", "Sent %1$s", "Read %1$s", "Retry", "Delete",
 "%1$s is typing…", "Typing…", "No connection", "Connecting…", "Connected", "Live updates paused",
 "You can’t send messages in this conversation.", "Sign in to send messages.", "Support chat is unavailable right now.",
 "Load earlier messages", "Latest", "New reply", "Offer", "Review", "No longer available", "Rate on Google Play",
 "View offer", "Too long: %1$d / %2$d characters", "Couldn’t load this photo.", "Files must be 10 MB or smaller.",
 "Only PDF, TXT, ZIP and LOG files can be sent.", "Up to 5 files at a time.", "Photo", "Open photo", "Close photo",
 "Uploading %1$d%%", "Support team", "You", "%1$s: %2$s", "Support", "Replies from the support team"]

L["es"] = ["Soporte", "¿Cómo podemos ayudarte?", "Envíanos un mensaje y te responderemos aquí mismo.", "Mensaje", "Enviar",
 "Adjuntar archivo", "Adjuntar", "Hasta 5 fotos o archivos por mensaje", "Fotos", "Cámara", "Archivo", "%1$d de %2$d archivos",
 "Quitar adjunto", "Cerrar", "Enviando…", "No entregado", "Enviado %1$s", "Leído %1$s", "Reintentar", "Eliminar",
 "%1$s está escribiendo…", "Escribiendo…", "Sin conexión", "Conectando…", "Conectado", "Actualizaciones en pausa",
 "No puedes enviar mensajes en esta conversación.", "Inicia sesión para enviar mensajes.", "El chat de soporte no está disponible ahora.",
 "Cargar mensajes anteriores", "Últimos", "Nueva respuesta", "Oferta", "Reseña", "Ya no está disponible", "Valorar en Google Play",
 "Ver oferta", "Demasiado largo: %1$d / %2$d caracteres", "No se pudo cargar esta foto.", "Los archivos deben ocupar 10 MB o menos.",
 "Solo se pueden enviar archivos PDF, TXT, ZIP y LOG.", "Hasta 5 archivos a la vez.", "Foto", "Abrir foto", "Cerrar foto",
 "Subiendo %1$d %%", "Equipo de soporte", "Tú", "%1$s: %2$s", "Soporte", "Respuestas del equipo de soporte"]

L["fr"] = ["Assistance", "Comment pouvons-nous vous aider ?", "Envoyez-nous un message, nous vous répondrons ici.", "Message", "Envoyer",
 "Ajouter une pièce jointe", "Joindre", "Jusqu’à 5 photos ou fichiers par message", "Photos", "Appareil photo", "Fichier", "%1$d fichiers sur %2$d",
 "Retirer la pièce jointe", "Fermer", "Envoi…", "Non distribué", "Envoyé %1$s", "Lu %1$s", "Réessayer", "Supprimer",
 "%1$s écrit…", "Écrit…", "Pas de connexion", "Connexion…", "Connecté", "Mises à jour en pause",
 "Vous ne pouvez pas envoyer de messages dans cette conversation.", "Connectez-vous pour envoyer des messages.", "Le chat d’assistance est indisponible pour le moment.",
 "Charger les messages précédents", "Récents", "Nouvelle réponse", "Offre", "Avis", "N’est plus disponible", "Noter sur Google Play",
 "Voir l’offre", "Trop long : %1$d / %2$d caractères", "Impossible de charger cette photo.", "Les fichiers doivent faire 10 Mo maximum.",
 "Seuls les fichiers PDF, TXT, ZIP et LOG peuvent être envoyés.", "Jusqu’à 5 fichiers à la fois.", "Photo", "Ouvrir la photo", "Fermer la photo",
 "Envoi %1$d %%", "Équipe d’assistance", "Vous", "%1$s : %2$s", "Assistance", "Réponses de l’équipe d’assistance"]

L["de"] = ["Support", "Wie können wir helfen?", "Schreib uns eine Nachricht – wir antworten direkt hier.", "Nachricht", "Senden",
 "Anhang hinzufügen", "Anhängen", "Bis zu 5 Fotos oder Dateien pro Nachricht", "Fotos", "Kamera", "Datei", "%1$d von %2$d Dateien",
 "Anhang entfernen", "Schließen", "Wird gesendet…", "Nicht zugestellt", "Gesendet %1$s", "Gelesen %1$s", "Erneut senden", "Löschen",
 "%1$s schreibt…", "Schreibt…", "Keine Verbindung", "Verbinden…", "Verbunden", "Live-Updates pausiert",
 "Du kannst in dieser Unterhaltung keine Nachrichten senden.", "Melde dich an, um Nachrichten zu senden.", "Der Support-Chat ist gerade nicht verfügbar.",
 "Ältere Nachrichten laden", "Neueste", "Neue Antwort", "Angebot", "Bewertung", "Nicht mehr verfügbar", "Bei Google Play bewerten",
 "Angebot ansehen", "Zu lang: %1$d / %2$d Zeichen", "Dieses Foto konnte nicht geladen werden.", "Dateien dürfen höchstens 10 MB groß sein.",
 "Nur PDF-, TXT-, ZIP- und LOG-Dateien können gesendet werden.", "Bis zu 5 Dateien auf einmal.", "Foto", "Foto öffnen", "Foto schließen",
 "Wird hochgeladen: %1$d %%", "Support-Team", "Du", "%1$s: %2$s", "Support", "Antworten des Support-Teams"]

L["it"] = ["Assistenza", "Come possiamo aiutarti?", "Scrivici un messaggio e ti risponderemo qui.", "Messaggio", "Invia",
 "Aggiungi allegato", "Allega", "Fino a 5 foto o file per messaggio", "Foto", "Fotocamera", "File", "%1$d di %2$d file",
 "Rimuovi allegato", "Chiudi", "Invio in corso…", "Non consegnato", "Inviato %1$s", "Letto %1$s", "Riprova", "Elimina",
 "%1$s sta scrivendo…", "Sta scrivendo…", "Nessuna connessione", "Connessione…", "Connesso", "Aggiornamenti in pausa",
 "Non puoi inviare messaggi in questa conversazione.", "Accedi per inviare messaggi.", "La chat di assistenza non è disponibile al momento.",
 "Carica messaggi precedenti", "Più recenti", "Nuova risposta", "Offerta", "Recensione", "Non più disponibile", "Valuta su Google Play",
 "Vedi offerta", "Troppo lungo: %1$d / %2$d caratteri", "Impossibile caricare questa foto.", "I file devono essere al massimo di 10 MB.",
 "Puoi inviare solo file PDF, TXT, ZIP e LOG.", "Fino a 5 file alla volta.", "Foto", "Apri foto", "Chiudi foto",
 "Caricamento %1$d%%", "Team di assistenza", "Tu", "%1$s: %2$s", "Assistenza", "Risposte del team di assistenza"]

L["pt-rBR"] = ["Suporte", "Como podemos ajudar?", "Envie uma mensagem e responderemos por aqui.", "Mensagem", "Enviar",
 "Adicionar anexo", "Anexar", "Até 5 fotos ou arquivos por mensagem", "Fotos", "Câmera", "Arquivo", "%1$d de %2$d arquivos",
 "Remover anexo", "Fechar", "Enviando…", "Não entregue", "Enviada %1$s", "Lida %1$s", "Tentar de novo", "Excluir",
 "%1$s está digitando…", "Digitando…", "Sem conexão", "Conectando…", "Conectado", "Atualizações pausadas",
 "Você não pode enviar mensagens nesta conversa.", "Entre na sua conta para enviar mensagens.", "O chat de suporte está indisponível no momento.",
 "Carregar mensagens anteriores", "Recentes", "Nova resposta", "Oferta", "Avaliação", "Não está mais disponível", "Avaliar no Google Play",
 "Ver oferta", "Muito longo: %1$d / %2$d caracteres", "Não foi possível carregar esta foto.", "Os arquivos devem ter até 10 MB.",
 "Só é possível enviar arquivos PDF, TXT, ZIP e LOG.", "Até 5 arquivos por vez.", "Foto", "Abrir foto", "Fechar foto",
 "Enviando %1$d%%", "Equipe de suporte", "Você", "%1$s: %2$s", "Suporte", "Respostas da equipe de suporte"]

L["nl"] = ["Support", "Waarmee kunnen we helpen?", "Stuur ons een bericht, dan antwoorden we hier.", "Bericht", "Versturen",
 "Bijlage toevoegen", "Bijvoegen", "Maximaal 5 foto’s of bestanden per bericht", "Foto’s", "Camera", "Bestand", "%1$d van %2$d bestanden",
 "Bijlage verwijderen", "Sluiten", "Versturen…", "Niet afgeleverd", "Verstuurd %1$s", "Gelezen %1$s", "Opnieuw", "Verwijderen",
 "%1$s typt…", "Typt…", "Geen verbinding", "Verbinden…", "Verbonden", "Live-updates gepauzeerd",
 "Je kunt in dit gesprek geen berichten versturen.", "Log in om berichten te versturen.", "De supportchat is nu niet beschikbaar.",
 "Eerdere berichten laden", "Nieuwste", "Nieuw antwoord", "Aanbieding", "Beoordeling", "Niet meer beschikbaar", "Beoordelen in Google Play",
 "Aanbieding bekijken", "Te lang: %1$d / %2$d tekens", "Deze foto kan niet worden geladen.", "Bestanden mogen maximaal 10 MB zijn.",
 "Alleen PDF-, TXT-, ZIP- en LOG-bestanden kunnen worden verstuurd.", "Maximaal 5 bestanden tegelijk.", "Foto", "Foto openen", "Foto sluiten",
 "Uploaden %1$d%%", "Supportteam", "Jij", "%1$s: %2$s", "Support", "Antwoorden van het supportteam"]

L["sv"] = ["Support", "Hur kan vi hjälpa till?", "Skicka ett meddelande så svarar vi här.", "Meddelande", "Skicka",
 "Lägg till bilaga", "Bifoga", "Upp till 5 foton eller filer per meddelande", "Foton", "Kamera", "Fil", "%1$d av %2$d filer",
 "Ta bort bilaga", "Stäng", "Skickar…", "Inte levererat", "Skickat %1$s", "Läst %1$s", "Försök igen", "Radera",
 "%1$s skriver…", "Skriver…", "Ingen anslutning", "Ansluter…", "Ansluten", "Liveuppdateringar pausade",
 "Du kan inte skicka meddelanden i den här konversationen.", "Logga in för att skicka meddelanden.", "Supportchatten är inte tillgänglig just nu.",
 "Läs in tidigare meddelanden", "Senaste", "Nytt svar", "Erbjudande", "Recension", "Inte längre tillgängligt", "Betygsätt på Google Play",
 "Visa erbjudande", "För långt: %1$d / %2$d tecken", "Det gick inte att läsa in fotot.", "Filer får vara högst 10 MB.",
 "Endast PDF-, TXT-, ZIP- och LOG-filer kan skickas.", "Upp till 5 filer åt gången.", "Foto", "Öppna foto", "Stäng foto",
 "Laddar upp %1$d %%", "Supportteamet", "Du", "%1$s: %2$s", "Support", "Svar från supportteamet"]

L["pl"] = ["Pomoc", "Jak możemy pomóc?", "Napisz do nas, a odpowiemy tutaj.", "Wiadomość", "Wyślij",
 "Dodaj załącznik", "Załącz", "Do 5 zdjęć lub plików w wiadomości", "Zdjęcia", "Aparat", "Plik", "%1$d z %2$d plików",
 "Usuń załącznik", "Zamknij", "Wysyłanie…", "Nie dostarczono", "Wysłano %1$s", "Przeczytano %1$s", "Ponów", "Usuń",
 "%1$s pisze…", "Pisze…", "Brak połączenia", "Łączenie…", "Połączono", "Aktualizacje wstrzymane",
 "Nie możesz wysyłać wiadomości w tej rozmowie.", "Zaloguj się, aby wysyłać wiadomości.", "Czat pomocy jest teraz niedostępny.",
 "Wczytaj wcześniejsze wiadomości", "Najnowsze", "Nowa odpowiedź", "Oferta", "Ocena", "Już niedostępne", "Oceń w Google Play",
 "Zobacz ofertę", "Za długie: %1$d / %2$d znaków", "Nie udało się wczytać zdjęcia.", "Pliki mogą mieć maksymalnie 10 MB.",
 "Można wysyłać tylko pliki PDF, TXT, ZIP i LOG.", "Do 5 plików naraz.", "Zdjęcie", "Otwórz zdjęcie", "Zamknij zdjęcie",
 "Przesyłanie %1$d%%", "Zespół pomocy", "Ty", "%1$s: %2$s", "Pomoc", "Odpowiedzi zespołu pomocy"]

L["tr"] = ["Destek", "Nasıl yardımcı olabiliriz?", "Bize mesaj gönderin, buradan yanıtlayalım.", "Mesaj", "Gönder",
 "Ek ekle", "Ekle", "Mesaj başına en fazla 5 fotoğraf veya dosya", "Fotoğraflar", "Kamera", "Dosya", "%1$d / %2$d dosya",
 "Eki kaldır", "Kapat", "Gönderiliyor…", "İletilmedi", "Gönderildi %1$s", "Okundu %1$s", "Tekrar dene", "Sil",
 "%1$s yazıyor…", "Yazıyor…", "Bağlantı yok", "Bağlanıyor…", "Bağlandı", "Canlı güncellemeler duraklatıldı",
 "Bu konuşmada mesaj gönderemezsiniz.", "Mesaj göndermek için giriş yapın.", "Destek sohbeti şu anda kullanılamıyor.",
 "Önceki mesajları yükle", "En yeni", "Yeni yanıt", "Teklif", "Değerlendirme", "Artık mevcut değil", "Google Play’de puan ver",
 "Teklifi gör", "Çok uzun: %1$d / %2$d karakter", "Bu fotoğraf yüklenemedi.", "Dosyalar en fazla 10 MB olabilir.",
 "Yalnızca PDF, TXT, ZIP ve LOG dosyaları gönderilebilir.", "Aynı anda en fazla 5 dosya.", "Fotoğraf", "Fotoğrafı aç", "Fotoğrafı kapat",
 "Yükleniyor %%%1$d", "Destek ekibi", "Siz", "%1$s: %2$s", "Destek", "Destek ekibinden yanıtlar"]

L["ru"] = ["Поддержка", "Чем можем помочь?", "Напишите нам — ответим прямо здесь.", "Сообщение", "Отправить",
 "Добавить вложение", "Вложения", "До 5 фото или файлов в сообщении", "Фото", "Камера", "Файл", "%1$d из %2$d файлов",
 "Удалить вложение", "Закрыть", "Отправка…", "Не доставлено", "Отправлено %1$s", "Прочитано %1$s", "Повторить", "Удалить",
 "%1$s печатает…", "Печатает…", "Нет подключения", "Подключение…", "Подключено", "Обновления приостановлены",
 "Вы не можете отправлять сообщения в этом чате.", "Войдите, чтобы отправлять сообщения.", "Чат поддержки сейчас недоступен.",
 "Загрузить предыдущие сообщения", "Новые", "Новый ответ", "Предложение", "Отзыв", "Больше не доступно", "Оценить в Google Play",
 "Посмотреть предложение", "Слишком длинно: %1$d / %2$d символов", "Не удалось загрузить фото.", "Размер файла — не более 10 МБ.",
 "Можно отправлять только файлы PDF, TXT, ZIP и LOG.", "Не более 5 файлов за раз.", "Фото", "Открыть фото", "Закрыть фото",
 "Загрузка %1$d %%", "Команда поддержки", "Вы", "%1$s: %2$s", "Поддержка", "Ответы команды поддержки"]

L["uk"] = ["Підтримка", "Чим можемо допомогти?", "Напишіть нам — відповімо просто тут.", "Повідомлення", "Надіслати",
 "Додати вкладення", "Вкладення", "До 5 фото чи файлів у повідомленні", "Фото", "Камера", "Файл", "%1$d з %2$d файлів",
 "Вилучити вкладення", "Закрити", "Надсилання…", "Не доставлено", "Надіслано %1$s", "Прочитано %1$s", "Повторити", "Видалити",
 "%1$s друкує…", "Друкує…", "Немає з’єднання", "З’єднання…", "З’єднано", "Оновлення призупинено",
 "Ви не можете надсилати повідомлення в цій розмові.", "Увійдіть, щоб надсилати повідомлення.", "Чат підтримки зараз недоступний.",
 "Завантажити попередні повідомлення", "Нові", "Нова відповідь", "Пропозиція", "Відгук", "Більше не доступно", "Оцінити в Google Play",
 "Переглянути пропозицію", "Задовго: %1$d / %2$d символів", "Не вдалося завантажити фото.", "Розмір файлу — не більше 10 МБ.",
 "Можна надсилати лише файли PDF, TXT, ZIP і LOG.", "Не більше 5 файлів за раз.", "Фото", "Відкрити фото", "Закрити фото",
 "Завантаження %1$d %%", "Команда підтримки", "Ви", "%1$s: %2$s", "Підтримка", "Відповіді команди підтримки"]

L["ar"] = ["الدعم", "كيف يمكننا مساعدتك؟", "أرسل لنا رسالة وسنرد عليك هنا.", "رسالة", "إرسال",
 "إضافة مرفق", "إرفاق", "حتى 5 صور أو ملفات في كل رسالة", "الصور", "الكاميرا", "ملف", "%1$d من %2$d ملفات",
 "إزالة المرفق", "إغلاق", "جارٍ الإرسال…", "لم تُسلَّم", "أُرسلت %1$s", "قُرئت %1$s", "إعادة المحاولة", "حذف",
 "%1$s يكتب…", "يكتب…", "لا يوجد اتصال", "جارٍ الاتصال…", "متصل", "التحديثات المباشرة متوقفة مؤقتًا",
 "لا يمكنك إرسال رسائل في هذه المحادثة.", "سجّل الدخول لإرسال الرسائل.", "دردشة الدعم غير متاحة الآن.",
 "تحميل الرسائل السابقة", "الأحدث", "رد جديد", "عرض", "تقييم", "لم يعد متاحًا", "التقييم على Google Play",
 "عرض التفاصيل", "طويل جدًا: %1$d / %2$d حرفًا", "تعذّر تحميل هذه الصورة.", "يجب ألا يتجاوز حجم الملف 10 ميغابايت.",
 "يمكن إرسال ملفات PDF وTXT وZIP وLOG فقط.", "حتى 5 ملفات في المرة الواحدة.", "صورة", "فتح الصورة", "إغلاق الصورة",
 "جارٍ الرفع %1$d%%", "فريق الدعم", "أنت", "%1$s: %2$s", "الدعم", "ردود فريق الدعم"]

L["iw"] = ["תמיכה", "איך נוכל לעזור?", "שלחו לנו הודעה ונענה כאן.", "הודעה", "שליחה",
 "הוספת קובץ מצורף", "צירוף", "עד 5 תמונות או קבצים בהודעה", "תמונות", "מצלמה", "קובץ", "%1$d מתוך %2$d קבצים",
 "הסרת הקובץ המצורף", "סגירה", "שולח…", "לא נמסרה", "נשלחה %1$s", "נקראה %1$s", "ניסיון חוזר", "מחיקה",
 "%1$s מקליד/ה…", "מקליד/ה…", "אין חיבור", "מתחבר…", "מחובר", "העדכונים החיים מושהים",
 "אי אפשר לשלוח הודעות בשיחה הזו.", "יש להתחבר כדי לשלוח הודעות.", "צ׳אט התמיכה לא זמין כרגע.",
 "טעינת הודעות קודמות", "האחרונות", "תשובה חדשה", "הצעה", "דירוג", "כבר לא זמין", "דירוג ב-Google Play",
 "צפייה בהצעה", "ארוך מדי: %1$d / %2$d תווים", "לא ניתן לטעון את התמונה.", "גודל הקבצים מוגבל ל-10MB.",
 "אפשר לשלוח רק קובצי PDF,‏ TXT,‏ ZIP ו-LOG.", "עד 5 קבצים בכל פעם.", "תמונה", "פתיחת התמונה", "סגירת התמונה",
 "מעלה %1$d%%", "צוות התמיכה", "את/ה", "%1$s: %2$s", "תמיכה", "תשובות מצוות התמיכה"]

L["hi"] = ["सहायता", "हम आपकी कैसे मदद कर सकते हैं?", "हमें संदेश भेजें, हम यहीं जवाब देंगे।", "संदेश", "भेजें",
 "अटैचमेंट जोड़ें", "अटैच करें", "हर संदेश में 5 फ़ोटो या फ़ाइलें तक", "फ़ोटो", "कैमरा", "फ़ाइल", "%2$d में से %1$d फ़ाइलें",
 "अटैचमेंट हटाएं", "बंद करें", "भेजा जा रहा है…", "डिलीवर नहीं हुआ", "भेजा गया %1$s", "पढ़ा गया %1$s", "फिर कोशिश करें", "मिटाएं",
 "%1$s लिख रहे हैं…", "लिख रहे हैं…", "कोई कनेक्शन नहीं", "कनेक्ट हो रहा है…", "कनेक्ट हो गया", "लाइव अपडेट रुके हुए हैं",
 "आप इस बातचीत में संदेश नहीं भेज सकते।", "संदेश भेजने के लिए साइन इन करें।", "सहायता चैट अभी उपलब्ध नहीं है।",
 "पिछले संदेश लोड करें", "नवीनतम", "नया जवाब", "ऑफ़र", "समीक्षा", "अब उपलब्ध नहीं", "Google Play पर रेटिंग दें",
 "ऑफ़र देखें", "बहुत लंबा: %1$d / %2$d वर्ण", "यह फ़ोटो लोड नहीं हो सकी।", "फ़ाइलें 10 MB या उससे छोटी होनी चाहिए।",
 "केवल PDF, TXT, ZIP और LOG फ़ाइलें भेजी जा सकती हैं।", "एक बार में 5 फ़ाइलें तक।", "फ़ोटो", "फ़ोटो खोलें", "फ़ोटो बंद करें",
 "अपलोड हो रहा है %1$d%%", "सहायता टीम", "आप", "%1$s: %2$s", "सहायता", "सहायता टीम के जवाब"]

L["ja"] = ["サポート", "どのようなご用件ですか？", "メッセージを送信いただければ、ここで返信します。", "メッセージ", "送信",
 "添付ファイルを追加", "添付", "1 件のメッセージにつき写真またはファイル 5 個まで", "写真", "カメラ", "ファイル", "%1$d / %2$d ファイル",
 "添付ファイルを削除", "閉じる", "送信中…", "未配信", "送信済み %1$s", "既読 %1$s", "再試行", "削除",
 "%1$s さんが入力中…", "入力中…", "接続されていません", "接続中…", "接続しました", "ライブ更新を一時停止中",
 "この会話ではメッセージを送信できません。", "メッセージを送信するにはログインしてください。", "現在サポートチャットはご利用いただけません。",
 "以前のメッセージを読み込む", "最新", "新しい返信", "特典", "レビュー", "提供終了", "Google Play で評価",
 "特典を見る", "長すぎます：%1$d / %2$d 文字", "この写真を読み込めませんでした。", "ファイルは 10 MB 以下にしてください。",
 "送信できるのは PDF、TXT、ZIP、LOG ファイルのみです。", "一度に 5 ファイルまで。", "写真", "写真を開く", "写真を閉じる",
 "アップロード中 %1$d%%", "サポートチーム", "あなた", "%1$s：%2$s", "サポート", "サポートチームからの返信"]

L["ko"] = ["고객 지원", "무엇을 도와드릴까요?", "메시지를 보내 주시면 여기에서 답변해 드릴게요.", "메시지", "보내기",
 "첨부 파일 추가", "첨부", "메시지당 사진 또는 파일 최대 5개", "사진", "카메라", "파일", "파일 %1$d/%2$d개",
 "첨부 파일 삭제", "닫기", "보내는 중…", "전송되지 않음", "보냄 %1$s", "읽음 %1$s", "다시 시도", "삭제",
 "%1$s님이 입력 중…", "입력 중…", "연결 없음", "연결 중…", "연결됨", "실시간 업데이트 일시중지됨",
 "이 대화에서는 메시지를 보낼 수 없습니다.", "메시지를 보내려면 로그인하세요.", "지금은 고객 지원 채팅을 사용할 수 없습니다.",
 "이전 메시지 불러오기", "최신", "새 답변", "혜택", "리뷰", "더 이상 제공되지 않음", "Google Play에서 평가하기",
 "혜택 보기", "너무 깁니다: %1$d/%2$d자", "사진을 불러올 수 없습니다.", "파일은 10MB 이하여야 합니다.",
 "PDF, TXT, ZIP, LOG 파일만 보낼 수 있습니다.", "한 번에 최대 5개 파일.", "사진", "사진 열기", "사진 닫기",
 "업로드 중 %1$d%%", "고객 지원팀", "나", "%1$s: %2$s", "고객 지원", "고객 지원팀의 답변"]

L["zh-rCN"] = ["支持", "有什么可以帮你？", "给我们发消息，我们会在这里回复你。", "消息", "发送",
 "添加附件", "附件", "每条消息最多 5 张照片或 5 个文件", "照片", "相机", "文件", "%1$d / %2$d 个文件",
 "移除附件", "关闭", "正在发送…", "未送达", "已发送 %1$s", "已读 %1$s", "重试", "删除",
 "%1$s 正在输入…", "正在输入…", "无网络连接", "正在连接…", "已连接", "实时更新已暂停",
 "你无法在此对话中发送消息。", "登录后才能发送消息。", "支持聊天暂时不可用。",
 "加载更早的消息", "最新", "新回复", "优惠", "评分", "已失效", "在 Google Play 上评分",
 "查看优惠", "太长了：%1$d / %2$d 个字符", "无法加载这张照片。", "文件不能超过 10 MB。",
 "只能发送 PDF、TXT、ZIP 和 LOG 文件。", "一次最多 5 个文件。", "照片", "打开照片", "关闭照片",
 "正在上传 %1$d%%", "支持团队", "你", "%1$s：%2$s", "支持", "来自支持团队的回复"]

L["zh-rTW"] = ["支援", "需要什麼協助？", "傳訊息給我們，我們會在這裡回覆你。", "訊息", "傳送",
 "新增附件", "附件", "每則訊息最多 5 張相片或 5 個檔案", "相片", "相機", "檔案", "%1$d / %2$d 個檔案",
 "移除附件", "關閉", "傳送中…", "未送達", "已傳送 %1$s", "已讀 %1$s", "重試", "刪除",
 "%1$s 正在輸入…", "正在輸入…", "沒有網路連線", "連線中…", "已連線", "即時更新已暫停",
 "你無法在這段對話中傳送訊息。", "登入後才能傳送訊息。", "支援聊天目前無法使用。",
 "載入較早的訊息", "最新", "新回覆", "優惠", "評分", "已失效", "在 Google Play 評分",
 "查看優惠", "太長了：%1$d / %2$d 個字元", "無法載入這張相片。", "檔案不得超過 10 MB。",
 "只能傳送 PDF、TXT、ZIP 和 LOG 檔案。", "一次最多 5 個檔案。", "相片", "開啟相片", "關閉相片",
 "上傳中 %1$d%%", "支援團隊", "你", "%1$s：%2$s", "支援", "來自支援團隊的回覆"]

L["in"] = ["Dukungan", "Ada yang bisa kami bantu?", "Kirim pesan dan kami akan membalas di sini.", "Pesan", "Kirim",
 "Tambahkan lampiran", "Lampirkan", "Hingga 5 foto atau file per pesan", "Foto", "Kamera", "File", "%1$d dari %2$d file",
 "Hapus lampiran", "Tutup", "Mengirim…", "Tidak terkirim", "Terkirim %1$s", "Dibaca %1$s", "Coba lagi", "Hapus",
 "%1$s sedang mengetik…", "Mengetik…", "Tidak ada koneksi", "Menghubungkan…", "Terhubung", "Pembaruan langsung dijeda",
 "Anda tidak dapat mengirim pesan dalam percakapan ini.", "Masuk untuk mengirim pesan.", "Chat dukungan sedang tidak tersedia.",
 "Muat pesan sebelumnya", "Terbaru", "Balasan baru", "Penawaran", "Ulasan", "Tidak tersedia lagi", "Beri rating di Google Play",
 "Lihat penawaran", "Terlalu panjang: %1$d / %2$d karakter", "Tidak dapat memuat foto ini.", "Ukuran file maksimal 10 MB.",
 "Hanya file PDF, TXT, ZIP, dan LOG yang dapat dikirim.", "Hingga 5 file sekaligus.", "Foto", "Buka foto", "Tutup foto",
 "Mengupload %1$d%%", "Tim dukungan", "Anda", "%1$s: %2$s", "Dukungan", "Balasan dari tim dukungan"]

L["vi"] = ["Hỗ trợ", "Chúng tôi có thể giúp gì cho bạn?", "Hãy nhắn cho chúng tôi, chúng tôi sẽ trả lời ngay tại đây.", "Tin nhắn", "Gửi",
 "Thêm tệp đính kèm", "Đính kèm", "Tối đa 5 ảnh hoặc tệp mỗi tin nhắn", "Ảnh", "Máy ảnh", "Tệp", "%1$d/%2$d tệp",
 "Xóa tệp đính kèm", "Đóng", "Đang gửi…", "Chưa gửi được", "Đã gửi %1$s", "Đã xem %1$s", "Thử lại", "Xóa",
 "%1$s đang nhập…", "Đang nhập…", "Không có kết nối", "Đang kết nối…", "Đã kết nối", "Đã tạm dừng cập nhật trực tiếp",
 "Bạn không thể gửi tin nhắn trong cuộc trò chuyện này.", "Đăng nhập để gửi tin nhắn.", "Hiện không thể dùng trò chuyện hỗ trợ.",
 "Tải tin nhắn cũ hơn", "Mới nhất", "Trả lời mới", "Ưu đãi", "Đánh giá", "Không còn khả dụng", "Đánh giá trên Google Play",
 "Xem ưu đãi", "Quá dài: %1$d/%2$d ký tự", "Không tải được ảnh này.", "Tệp phải có dung lượng tối đa 10 MB.",
 "Chỉ có thể gửi tệp PDF, TXT, ZIP và LOG.", "Tối đa 5 tệp mỗi lần.", "Ảnh", "Mở ảnh", "Đóng ảnh",
 "Đang tải lên %1$d%%", "Nhóm hỗ trợ", "Bạn", "%1$s: %2$s", "Hỗ trợ", "Trả lời từ nhóm hỗ trợ"]

L["th"] = ["ฝ่ายช่วยเหลือ", "มีอะไรให้เราช่วยไหม", "ส่งข้อความถึงเรา แล้วเราจะตอบกลับที่นี่", "ข้อความ", "ส่ง",
 "เพิ่มไฟล์แนบ", "แนบ", "สูงสุด 5 รูปหรือไฟล์ต่อข้อความ", "รูปภาพ", "กล้อง", "ไฟล์", "%1$d จาก %2$d ไฟล์",
 "นำไฟล์แนบออก", "ปิด", "กำลังส่ง…", "ส่งไม่สำเร็จ", "ส่งแล้ว %1$s", "อ่านแล้ว %1$s", "ลองอีกครั้ง", "ลบ",
 "%1$s กำลังพิมพ์…", "กำลังพิมพ์…", "ไม่มีการเชื่อมต่อ", "กำลังเชื่อมต่อ…", "เชื่อมต่อแล้ว", "หยุดการอัปเดตสดชั่วคราว",
 "คุณส่งข้อความในการสนทนานี้ไม่ได้", "ลงชื่อเข้าใช้เพื่อส่งข้อความ", "แชทฝ่ายช่วยเหลือไม่พร้อมใช้งานในขณะนี้",
 "โหลดข้อความก่อนหน้า", "ล่าสุด", "คำตอบใหม่", "ข้อเสนอ", "รีวิว", "ไม่มีให้บริการแล้ว", "ให้คะแนนบน Google Play",
 "ดูข้อเสนอ", "ยาวเกินไป: %1$d / %2$d ตัวอักษร", "โหลดรูปนี้ไม่ได้", "ไฟล์ต้องมีขนาดไม่เกิน 10 MB",
 "ส่งได้เฉพาะไฟล์ PDF, TXT, ZIP และ LOG", "ครั้งละไม่เกิน 5 ไฟล์", "รูปภาพ", "เปิดรูป", "ปิดรูป",
 "กำลังอัปโหลด %1$d%%", "ทีมช่วยเหลือ", "คุณ", "%1$s: %2$s", "ฝ่ายช่วยเหลือ", "คำตอบจากทีมช่วยเหลือ"]


# App promo and code cards (PROTOCOL §5). `code_spoken` is the TalkBack label; its argument is the
# code spelled out one character at a time.
CARD_KEYS = """card_app card_code app_button app_installed code_copy code_copied code_copy_label code_redeem
code_redeem_ios code_redeem_android code_redeem_web code_expired code_expires code_spoken""".split()

C = {}

C[""] = ["App", "Code", "Get it", "You already have it", "Copy", "Copied", "Copy code", "Redeem",
 "Redeem on iPhone", "Redeem on Android", "Redeem on the web", "Expired", "Valid until %1$s", "Code %1$s"]

C["es"] = ["App", "Código", "Obtener", "Ya la tienes", "Copiar", "Copiado", "Copiar código", "Canjear",
 "Canjéalo en el iPhone", "Canjéalo en Android", "Canjéalo en la web", "Caducado", "Válido hasta el %1$s", "Código %1$s"]

C["fr"] = ["Appli", "Code", "Obtenir", "Vous l’avez déjà", "Copier", "Copié", "Copier le code", "Échanger",
 "À échanger sur iPhone", "À échanger sur Android", "À échanger sur le web", "Expiré", "Valable jusqu’au %1$s", "Code %1$s"]

C["de"] = ["App", "Code", "Laden", "Hast du schon", "Kopieren", "Kopiert", "Code kopieren", "Einlösen",
 "Auf dem iPhone einlösen", "Auf Android einlösen", "Im Web einlösen", "Abgelaufen", "Gültig bis %1$s", "Code %1$s"]

C["it"] = ["App", "Codice", "Scarica", "Ce l’hai già", "Copia", "Copiato", "Copia codice", "Riscatta",
 "Riscatta su iPhone", "Riscatta su Android", "Riscatta sul web", "Scaduto", "Valido fino al %1$s", "Codice %1$s"]

C["pt-rBR"] = ["App", "Código", "Baixar", "Você já tem", "Copiar", "Copiado", "Copiar código", "Resgatar",
 "Resgate no iPhone", "Resgate no Android", "Resgate na web", "Expirado", "Válido até %1$s", "Código %1$s"]

C["nl"] = ["App", "Code", "Downloaden", "Heb je al", "Kopiëren", "Gekopieerd", "Code kopiëren", "Inwisselen",
 "Inwisselen op iPhone", "Inwisselen op Android", "Inwisselen op het web", "Verlopen", "Geldig tot %1$s", "Code %1$s"]

C["sv"] = ["App", "Kod", "Hämta", "Du har den redan", "Kopiera", "Kopierad", "Kopiera kod", "Lös in",
 "Lös in på iPhone", "Lös in på Android", "Lös in på webben", "Har gått ut", "Giltig till %1$s", "Kod %1$s"]

C["pl"] = ["Aplikacja", "Kod", "Pobierz", "Już ją masz", "Kopiuj", "Skopiowano", "Kopiuj kod", "Zrealizuj",
 "Zrealizuj na iPhonie", "Zrealizuj na Androidzie", "Zrealizuj w przeglądarce", "Wygasł", "Ważny do %1$s", "Kod %1$s"]

C["tr"] = ["Uygulama", "Kod", "İndir", "Zaten sende var", "Kopyala", "Kopyalandı", "Kodu kopyala", "Kullan",
 "iPhone’da kullan", "Android’de kullan", "Web’de kullan", "Süresi doldu", "%1$s tarihine kadar geçerli", "Kod %1$s"]

C["ru"] = ["Приложение", "Код", "Скачать", "У вас уже есть", "Копировать", "Скопировано", "Скопировать код", "Активировать",
 "Активируйте на iPhone", "Активируйте на Android", "Активируйте на сайте", "Срок истёк", "Действует до %1$s", "Код %1$s"]

C["uk"] = ["Застосунок", "Код", "Завантажити", "У вас уже є", "Копіювати", "Скопійовано", "Скопіювати код", "Активувати",
 "Активуйте на iPhone", "Активуйте на Android", "Активуйте на сайті", "Термін дії минув", "Діє до %1$s", "Код %1$s"]

C["ar"] = ["تطبيق", "رمز", "احصل عليه", "لديك هذا التطبيق بالفعل", "نسخ", "تم النسخ", "نسخ الرمز", "استرداد",
 "استرده على iPhone", "استرده على Android", "استرده على الويب", "منتهي الصلاحية", "صالح حتى %1$s", "الرمز %1$s"]

C["iw"] = ["אפליקציה", "קוד", "להורדה", "כבר יש לך אותה", "העתקה", "הועתק", "העתקת הקוד", "מימוש",
 "למימוש ב-iPhone", "למימוש ב-Android", "למימוש באתר", "פג תוקף", "בתוקף עד %1$s", "קוד %1$s"]

C["hi"] = ["ऐप", "कोड", "पाएं", "यह आपके पास पहले से है", "कॉपी करें", "कॉपी हो गया", "कोड कॉपी करें", "रिडीम करें",
 "iPhone पर रिडीम करें", "Android पर रिडीम करें", "वेब पर रिडीम करें", "समय-सीमा खत्म", "%1$s तक मान्य", "कोड %1$s"]

C["ja"] = ["アプリ", "コード", "入手", "インストール済みです", "コピー", "コピーしました", "コードをコピー", "引き換える",
 "iPhoneで引き換えてください", "Androidで引き換えてください", "ウェブで引き換えてください", "期限切れ", "%1$sまで有効", "コード %1$s"]

C["ko"] = ["앱", "코드", "받기", "이미 사용 중인 앱이에요", "복사", "복사됨", "코드 복사", "사용하기",
 "iPhone에서 사용하세요", "Android에서 사용하세요", "웹에서 사용하세요", "만료됨", "%1$s까지 유효", "코드 %1$s"]

C["zh-rCN"] = ["应用", "兑换码", "获取", "你已经有这个应用了", "复制", "已复制", "复制兑换码", "兑换",
 "请在 iPhone 上兑换", "请在 Android 上兑换", "请在网页上兑换", "已过期", "有效期至 %1$s", "兑换码 %1$s"]

C["zh-rTW"] = ["App", "代碼", "取得", "你已經有這個 App 了", "複製", "已複製", "複製代碼", "兌換",
 "請在 iPhone 上兌換", "請在 Android 上兌換", "請在網頁上兌換", "已過期", "有效期限至 %1$s", "代碼 %1$s"]

C["in"] = ["Aplikasi", "Kode", "Dapatkan", "Anda sudah memilikinya", "Salin", "Disalin", "Salin kode", "Tukarkan",
 "Tukarkan di iPhone", "Tukarkan di Android", "Tukarkan di web", "Kedaluwarsa", "Berlaku hingga %1$s", "Kode %1$s"]

C["vi"] = ["Ứng dụng", "Mã", "Tải về", "Bạn đã có ứng dụng này", "Sao chép", "Đã sao chép", "Sao chép mã", "Đổi mã",
 "Đổi mã trên iPhone", "Đổi mã trên Android", "Đổi mã trên web", "Đã hết hạn", "Có hiệu lực đến %1$s", "Mã %1$s"]

C["th"] = ["แอป", "โค้ด", "รับแอป", "คุณมีแอปนี้แล้ว", "คัดลอก", "คัดลอกแล้ว", "คัดลอกโค้ด", "แลกรับ",
 "แลกรับบน iPhone", "แลกรับบน Android", "แลกรับบนเว็บ", "หมดอายุแล้ว", "ใช้ได้ถึง %1$s", "โค้ด %1$s"]


def android_escape(v: str) -> str:
    v = escape(v)
    v = v.replace("'", "\\'").replace('"', '\\"')
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    return v


def main(res: str) -> None:
    assert set(C) == set(L), set(L) ^ set(C)
    keys = KEYS + CARD_KEYS
    for locale, base in L.items():
        assert len(base) == len(KEYS), (locale, len(base), len(KEYS))
        assert len(C[locale]) == len(CARD_KEYS), (locale, len(C[locale]), len(CARD_KEYS))
        values = base + C[locale]
        folder = os.path.join(res, "values" + ("-" + locale if locale else ""))
        os.makedirs(folder, exist_ok=True)
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<!-- Generated: every locale carries every key. Keep keys in step with values/strings.xml. -->',
                 '<resources>']
        for k, v in zip(keys, values):
            fmt = ' formatted="false"' if False else ''
            lines.append(f'    <string name="yaps_support_{k}"{fmt}>{android_escape(v)}</string>')
        lines.append('</resources>')
        with open(os.path.join(folder, "strings.xml"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    print(f"wrote {len(L)} locales x {len(KEYS) + len(CARD_KEYS)} strings")


if __name__ == "__main__":
    main(sys.argv[1])
