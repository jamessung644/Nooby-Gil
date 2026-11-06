// Creator-published YouTube Music destinations checked on 2026-11-05.
// Discovery metadata only: playlists remain hosted/maintained by their creators.
// Do not add an ID from an LLM response. Verify the creator's source link first.
// Theme tags are SCENIC suitability annotations, not creator-supplied metadata.
export const playlists = Object.freeze([
  {
    id: 'PLJH9QWrouDvRYVdjtWRGEmSyUFCq1GCb7', title: 'Lost in the Woods', creator: 'Indie Folk Central',
    mood: '분위기 있는 여유로운 인디 포크. 숲과 조용한 풍경에 어울리는 차분한 곡들.', themes: ['forest', 'mountain', 'water', 'lake'],
    sourceUrl: 'https://indiefolkcentral.com/playlists/lost-in-the-woods/', checkedAt: '2026-11-05',
  },
  {
    id: 'PLJH9QWrouDvTa4NQg_0PC6X8crgoTGLsj', title: 'Acoustic Indie Folk', creator: 'Indie Folk Central',
    mood: '기타와 목소리 중심의 담백한 어쿠스틱 인디 포크.', themes: ['lake', 'water', 'forest', 'countryside', 'all'],
    sourceUrl: 'https://indiefolkcentral.com/playlists/acoustic-indie-folk/', checkedAt: '2026-11-05',
  },
  {
    id: 'PLJH9QWrouDvQfAS2ITzV4ZqzanAMViOzd', title: 'Cozy Folk', creator: 'Indie Folk Central',
    mood: '따뜻하고 부드러운 포크. 편안한 분위기로 천천히 풍경을 감상할 때.', themes: ['lake', 'water', 'forest', 'mountain', 'countryside', 'all'],
    sourceUrl: 'https://indiefolkcentral.com/playlists/cozy-folk/', checkedAt: '2026-11-05',
  },
  {
    // The creator's discovery page uses an older name; verified the destination in Pleos.
    id: 'PLJH9QWrouDvSc53YzqvM-tgiMivWWVxca', title: 'Indie Roadtrip & Adventure', creator: 'Indie Folk Central',
    mood: '밝고 경쾌한 포크 팝. 기분 좋은 로드트립 분위기.', themes: ['coast', 'city', 'all'],
    sourceUrl: 'https://indiefolkcentral.com/playlists/feel-good-folk-pop/', checkedAt: '2026-11-05',
  },
  {
    id: 'PLa4yK1a5RlqLYBTMxrJqZEszZLjO7bLaa', title: 'NIGHT DRIVE 006', creator: 'OVERWERK',
    mood: 'OVERWERK가 큐레이션한 NIGHT DRIVE 시리즈. 도시 드라이브 분위기를 위한 후보.', themes: ['city', 'all'],
    sourceUrl: 'https://www.overwerk.com/music', checkedAt: '2026-11-05',
  },
  {
    id: 'PLa4yK1a5RlqLRchUjL_M0gJ6At5A-Ijyi', title: 'DAY DRIVE 001', creator: 'OVERWERK',
    mood: 'OVERWERK가 큐레이션한 DAY DRIVE 시리즈. 탁 트인 길과 일상 드라이브 분위기를 위한 후보.', themes: ['coast', 'all'],
    sourceUrl: 'https://www.overwerk.com/music', checkedAt: '2026-11-05',
  },
].map((item) => Object.freeze({ ...item, themes: Object.freeze(item.themes) })));
