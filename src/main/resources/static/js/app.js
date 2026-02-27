// ── 이미지 업로드 & 미리보기 ──

document.addEventListener('DOMContentLoaded', () => {
    const dropzone = document.getElementById('dropzone');
    const imageInput = document.getElementById('imageInput');
    const previewGrid = document.getElementById('previewGrid');
    const form = document.getElementById('generateForm');

    if (!dropzone) return;

    // Drag & Drop
    ['dragenter', 'dragover'].forEach(event => {
        dropzone.addEventListener(event, (e) => {
            e.preventDefault();
            dropzone.classList.add('dragover');
        });
    });

    ['dragleave', 'drop'].forEach(event => {
        dropzone.addEventListener(event, (e) => {
            e.preventDefault();
            dropzone.classList.remove('dragover');
        });
    });

    dropzone.addEventListener('drop', (e) => {
        imageInput.files = e.dataTransfer.files;
        showPreviews(e.dataTransfer.files);
    });

    imageInput.addEventListener('change', (e) => {
        showPreviews(e.target.files);
    });

    function showPreviews(files) {
        previewGrid.innerHTML = '';
        Array.from(files).slice(0, 10).forEach((file, index) => {
            const reader = new FileReader();
            reader.onload = (e) => {
                const div = document.createElement('div');
                div.className = 'preview-item';
                div.innerHTML = `
                    <img src="${e.target.result}" alt="사진 ${index + 1}">
                    <span style="position:absolute;bottom:4px;left:4px;background:rgba(0,0,0,0.6);color:#fff;padding:1px 6px;border-radius:4px;font-size:0.75rem;">${index + 1}</span>
                `;
                previewGrid.appendChild(div);
            };
            reader.readAsDataURL(file);
        });
    }

    // AJAX 폼 제출 — 오류 시 입력 데이터 유지
    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        const btn = document.getElementById('submitBtn');
        const btnText = btn.querySelector('.btn-text');
        const btnLoading = btn.querySelector('.btn-loading');
        const errorEl = document.getElementById('errorMessage');

        errorEl.style.display = 'none';

        const files = imageInput.files;
        if (!files || files.length === 0) {
            errorEl.textContent = '사진을 1장 이상 업로드해 주세요.';
            errorEl.style.display = 'block';
            return;
        }

        // 로딩 상태
        btn.disabled = true;
        btnText.style.display = 'none';
        btnLoading.style.display = 'inline';

        const formData = new FormData();
        Array.from(files).slice(0, 10).forEach(file => {
            formData.append('images', file);
        });
        formData.append('memo', document.getElementById('memo').value);
        formData.append('style', document.querySelector('input[name="style"]:checked').value);

        try {
            const response = await fetch('/generate', {
                method: 'POST',
                body: formData
            });

            const data = await response.json();

            if (!response.ok || data.error) {
                throw new Error(data.error || '글 생성에 실패했습니다.');
            }

            // 성공 — 결과 표시
            showResult(data);

        } catch (error) {
            // 실패 — 입력 데이터 그대로 유지, 에러만 표시
            errorEl.textContent = error.message;
            errorEl.style.display = 'block';
            window.scrollTo({ top: 0, behavior: 'smooth' });
        } finally {
            btn.disabled = false;
            btnText.style.display = 'inline';
            btnLoading.style.display = 'none';
        }
    });
});


// ── 결과 표시 ──

function showResult(post) {
    document.getElementById('inputSection').style.display = 'none';
    document.getElementById('resultSection').style.display = 'block';
    document.getElementById('errorMessage').style.display = 'none';

    document.getElementById('postTitle').textContent = post.title;
    document.getElementById('postBody').innerHTML = post.htmlContent;
    document.getElementById('sessionId').value = post.sessionId;
    document.getElementById('plainText').value = post.plainText;

    // 사진 삽입 가이드
    const guideEl = document.getElementById('imageGuide');
    const guideList = document.getElementById('imageGuideList');

    if (post.imagePositions && post.imagePositions.length > 0) {
        guideList.innerHTML = '';
        post.imagePositions.forEach(img => {
            const li = document.createElement('li');
            li.innerHTML = `<strong>사진${img.index}</strong>: ${img.fileName} — <em>${img.suggestedCaption}</em>`;
            guideList.appendChild(li);
        });
        guideEl.style.display = 'block';
    } else {
        guideEl.style.display = 'none';
    }

    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function backToEdit() {
    document.getElementById('resultSection').style.display = 'none';
    document.getElementById('inputSection').style.display = 'block';
    // 입력 데이터(메모, 스타일, 사진 미리보기)가 그대로 남아있음
}


// ── 복사 ──

function copyHtml() {
    const title = document.getElementById('postTitle')?.textContent || '';
    const body = document.getElementById('postBody')?.innerHTML || '';
    const html = `<h2>${title}</h2>${body}`;

    navigator.clipboard.writeText(html).then(() => {
        showToast('HTML이 클립보드에 복사되었습니다!');
    }).catch(() => fallbackCopy(html));
}

function copyText() {
    const title = document.getElementById('postTitle')?.textContent || '';
    const plainText = document.getElementById('plainText')?.value || '';
    const text = `${title}\n\n${plainText}`;

    navigator.clipboard.writeText(text).then(() => {
        showToast('텍스트가 클립보드에 복사되었습니다!');
    }).catch(() => fallbackCopy(text));
}

function fallbackCopy(text) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
    showToast('복사되었습니다!');
}


// ── 수정 ──

async function revisePost() {
    const sessionId = document.getElementById('sessionId')?.value;
    const input = document.getElementById('reviseInput');
    const instruction = input?.value?.trim();

    if (!instruction) return;

    const btn = document.getElementById('reviseBtn');
    const loading = document.getElementById('reviseLoading');

    btn.disabled = true;
    loading.style.display = 'block';

    try {
        const formData = new FormData();
        formData.append('sessionId', sessionId);
        formData.append('instruction', instruction);

        const response = await fetch('/revise', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (!response.ok || data.error) {
            throw new Error(data.error || '수정 요청 실패');
        }

        document.getElementById('postTitle').textContent = data.title;
        document.getElementById('postBody').innerHTML = data.htmlContent;
        document.getElementById('plainText').value = data.plainText;
        document.getElementById('sessionId').value = data.sessionId;

        input.value = '';
        showToast('수정이 완료되었습니다!');
    } catch (error) {
        showToast('수정 중 오류: ' + error.message);
    } finally {
        btn.disabled = false;
        loading.style.display = 'none';
    }
}


// ── 유틸 ──

function showToast(message) {
    const toast = document.getElementById('toast');
    if (!toast) return;

    toast.textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 2500);
}

document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.id === 'reviseInput') {
        revisePost();
    }
});
