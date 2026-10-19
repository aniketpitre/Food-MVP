// Catalog used to seed a fresh database. Restaurant names, dishes and coordinates are
// invented for the demo (see docs/WorthAbite_Android_MVP_Plan.md); replace them with the
// places you actually visit before inviting real users.

export const cities = [
  { slug: 'nashik', name: 'Nashik', state: 'Maharashtra', lat: 20.0059, lng: 73.7898, radiusKm: 25, active: true },
  { slug: 'pune', name: 'Pune', state: 'Maharashtra', lat: 18.5204, lng: 73.8567, radiusKm: 30, active: false },
  { slug: 'mumbai', name: 'Mumbai', state: 'Maharashtra', lat: 19.076, lng: 72.8777, radiusKm: 35, active: false },
];

export const categories = [
  { slug: 'misal', name: 'Misal', emoji: '🌶️' },
  { slug: 'snacks', name: 'Vada Pav & Snacks', emoji: '🥟' },
  { slug: 'south-indian', name: 'Dosa & South Indian', emoji: '🥞' },
  { slug: 'biryani', name: 'Biryani', emoji: '🍛' },
  { slug: 'thali', name: 'Thali', emoji: '🍱' },
  { slug: 'chai-coffee', name: 'Chai & Coffee', emoji: '☕' },
  { slug: 'desserts', name: 'Desserts & Sweets', emoji: '🍮' },
  { slug: 'street-food', name: 'Street Food', emoji: '🌯' },
  { slug: 'burgers', name: 'Burgers & Sandwiches', emoji: '🍔' },
  { slug: 'pizza-pasta', name: 'Pizza & Pasta', emoji: '🍕' },
  { slug: 'chinese', name: 'Chinese', emoji: '🥡' },
  { slug: 'breakfast', name: 'Breakfast', emoji: '🍳' },
  // Added later: existing servers get these on start-up (syncVocabulary), keeping the ids above.
  { slug: 'north-indian', name: 'North Indian', emoji: '🍲' },
  { slug: 'tandoor', name: 'Tandoor & Kebabs', emoji: '🍢' },
  { slug: 'maharashtrian', name: 'Maharashtrian', emoji: '🫓' },
  { slug: 'non-veg-curry', name: 'Chicken & Mutton', emoji: '🍗' },
  { slug: 'seafood', name: 'Fish & Seafood', emoji: '🦐' },
  { slug: 'rolls', name: 'Rolls & Wraps', emoji: '🌮' },
  { slug: 'momos', name: 'Momos', emoji: '🥠' },
  { slug: 'ice-cream', name: 'Ice Cream & Shakes', emoji: '🍨' },
  { slug: 'bakery', name: 'Bakery & Cakes', emoji: '🧁' },
  { slug: 'drinks', name: 'Juices & Drinks', emoji: '🥤' },
  { slug: 'healthy', name: 'Salads & Healthy', emoji: '🥗' },
  { slug: 'gujarati', name: 'Gujarati & Farsan', emoji: '🥘' },
  { slug: 'fries', name: 'Fries & Sides', emoji: '🍟' },
  { slug: 'soups', name: 'Soups', emoji: '🥣' },
  { slug: 'eggs', name: 'Egg Specials', emoji: '🥚' },
  { slug: 'upvas', name: 'Upvas (Fasting)', emoji: '🌾' },
  { slug: 'mexican', name: 'Mexican', emoji: '🌮' },
  { slug: 'asian', name: 'Thai, Japanese & Korean', emoji: '🍜' },
  { slug: 'middle-eastern', name: 'Falafel & Lebanese', emoji: '🧆' },
  { slug: 'continental', name: 'Continental & Sizzlers', emoji: '🍽️' },
  { slug: 'waffles', name: 'Waffles & Pancakes', emoji: '🧇' },
];

/**
 * Cuisines: a wider way in than dish types. A dish belongs to a cuisine when its type is one
 * of [categories], its name says so ([terms]), or its place's cuisine (from OpenStreetMap or
 * the owner) mentions one of the [terms]. "Italian" finds pizza, pasta and the Italian place.
 */
export interface Cuisine { slug: string; name: string; emoji: string; categories: string[]; terms: string[] }
export const cuisines: Cuisine[] = [
  { slug: 'maharashtrian', name: 'Maharashtrian', emoji: '🫓', categories: ['misal', 'maharashtrian', 'upvas'], terms: ['maharashtrian', 'marathi', 'malvani', 'kolhapuri', 'puran poli', 'thalipeeth', 'zunka', 'bhakri', 'pithla'] },
  { slug: 'north-indian', name: 'North Indian & Punjabi', emoji: '🍲', categories: ['north-indian', 'tandoor'], terms: ['north indian', 'punjabi', 'paneer', 'dal makhani', 'butter chicken', 'naan', 'kulcha', 'chole'] },
  { slug: 'south-indian', name: 'South Indian', emoji: '🥞', categories: ['south-indian'], terms: ['south indian', 'udupi', 'dosa', 'idli', 'uttapam', 'appam', 'chettinad', 'kerala'] },
  { slug: 'street', name: 'Street Food', emoji: '🌯', categories: ['street-food', 'snacks', 'rolls', 'momos'], terms: ['street food', 'chaat', 'vada pav', 'pani puri', 'bhel'] },
  { slug: 'chinese', name: 'Chinese & Indo-Chinese', emoji: '🥡', categories: ['chinese'], terms: ['chinese', 'manchurian', 'schezwan', 'szechuan', 'hakka', 'chilli'] },
  { slug: 'mughlai', name: 'Mughlai & Biryani', emoji: '🍛', categories: ['biryani', 'non-veg-curry'], terms: ['mughlai', 'hyderabadi', 'biryani', 'kebab', 'korma', 'nihari', 'awadhi', 'lucknowi'] },
  { slug: 'gujarati', name: 'Gujarati & Rajasthani', emoji: '🥘', categories: ['gujarati'], terms: ['gujarati', 'rajasthani', 'kathiyawadi', 'marwari', 'dal baati', 'dhokla', 'thepla'] },
  { slug: 'italian', name: 'Italian', emoji: '🍕', categories: ['pizza-pasta'], terms: ['italian', 'pizza', 'pasta', 'lasagna', 'risotto', 'bruschetta'] },
  { slug: 'american', name: 'Burgers & Fast Food', emoji: '🍔', categories: ['burgers', 'fries'], terms: ['burger', 'american', 'fast food', 'fried chicken', 'hot dog', 'sandwich'] },
  { slug: 'continental', name: 'Continental', emoji: '🍽️', categories: ['continental', 'soups'], terms: ['continental', 'european', 'international', 'sizzler', 'steak', 'grill'] },
  { slug: 'mexican', name: 'Mexican', emoji: '🌮', categories: ['mexican'], terms: ['mexican', 'tex mex', 'taco', 'burrito', 'nachos', 'quesadilla'] },
  { slug: 'asian', name: 'Thai, Japanese & Korean', emoji: '🍜', categories: ['asian'], terms: ['thai', 'japanese', 'korean', 'asian', 'sushi', 'ramen', 'pan asian', 'oriental', 'vietnamese'] },
  { slug: 'middle-eastern', name: 'Lebanese & Arabic', emoji: '🧆', categories: ['middle-eastern'], terms: ['lebanese', 'arabic', 'middle eastern', 'turkish', 'mediterranean', 'shawarma', 'falafel', 'hummus', 'mandi'] },
  { slug: 'seafood', name: 'Seafood & Coastal', emoji: '🦐', categories: ['seafood'], terms: ['seafood', 'fish', 'prawn', 'coastal', 'konkani', 'goan', 'mangalorean', 'surmai', 'pomfret'] },
  { slug: 'cafe', name: 'Cafe', emoji: '☕', categories: ['chai-coffee', 'waffles'], terms: ['cafe', 'coffee', 'coffee shop', 'tea', 'chai'] },
  { slug: 'bakery', name: 'Bakery & Desserts', emoji: '🧁', categories: ['bakery', 'desserts', 'ice-cream', 'waffles'], terms: ['bakery', 'cake', 'pastry', 'dessert', 'sweets', 'mithai', 'ice cream', 'confectionery', 'kulfi'] },
  { slug: 'drinks', name: 'Juices & Drinks', emoji: '🥤', categories: ['drinks'], terms: ['juice', 'shake', 'smoothie', 'lassi', 'mocktail', 'beverages', 'bubble tea'] },
  { slug: 'healthy', name: 'Healthy & Salads', emoji: '🥗', categories: ['healthy'], terms: ['healthy', 'salad', 'vegan', 'organic', 'protein', 'keto'] },
  { slug: 'breakfast', name: 'Breakfast', emoji: '🍳', categories: ['breakfast', 'eggs'], terms: ['breakfast', 'poha', 'upma', 'omelette', 'paratha'] },
  { slug: 'thali', name: 'Thali & Meals', emoji: '🍱', categories: ['thali'], terms: ['thali', 'unlimited', 'meals', 'bhojanalaya', 'khanaval', 'mess'] },
];

// canonical name, category slug, spellings people type
export const aliases: [string, string, string[]][] = [
  // The newer dish types first: a spelling belongs to the first type that claims it.
  ['Khandvi', 'gujarati', ['khandvi', 'khandavi']],
  ['Fafda Jalebi', 'gujarati', ['fafda', 'fafda jalebi']],
  ['Thepla', 'gujarati', ['thepla', 'methi thepla']],
  ['Gujarati Thali', 'gujarati', ['gujarati thali', 'kathiyawadi thali', 'rajasthani thali']],
  ['Dal Baati', 'gujarati', ['dal baati', 'dal bati', 'dal baati churma']],
  ['French Fries', 'fries', ['french fries', 'fries', 'finger chips', 'peri peri fries', 'cheese fries']],
  ['Potato Wedges', 'fries', ['potato wedges', 'wedges']],
  ['Manchow Soup', 'soups', ['manchow soup', 'manchow', 'veg manchow soup']],
  ['Tomato Soup', 'soups', ['tomato soup', 'cream of tomato']],
  ['Sweet Corn Soup', 'soups', ['sweet corn soup', 'corn soup']],
  ['Hot and Sour Soup', 'soups', ['hot and sour soup', 'hot n sour soup', 'lemon coriander soup']],
  ['Masala Omelette', 'eggs', ['omelette', 'omlette', 'masala omelette', 'cheese omelette', 'amlet', 'omelet']],
  ['Half Fry', 'eggs', ['half fry', 'egg half fry', 'sunny side up', 'boiled egg']],
  ['Upvas Thali', 'upvas', ['upvas thali', 'upvas', 'vrat thali', 'fasting thali', 'upvasache thali']],
  ['Sabudana Thalipeeth', 'upvas', ['sabudana thalipeeth', 'rajgira puri', 'shingada', 'varai bhat', 'bhagar']],
  ['Tacos', 'mexican', ['taco', 'tacos', 'veg tacos', 'chicken tacos']],
  ['Burrito', 'mexican', ['burrito', 'burrito bowl', 'burritos']],
  ['Nachos', 'mexican', ['nachos', 'loaded nachos', 'cheese nachos']],
  ['Quesadilla', 'mexican', ['quesadilla', 'quesadillas']],
  ['Sushi', 'asian', ['sushi', 'maki', 'sushi roll']],
  ['Ramen', 'asian', ['ramen', 'ramen bowl']],
  ['Thai Curry', 'asian', ['thai curry', 'green curry', 'red curry', 'thai green curry', 'pad thai']],
  ['Korean Fried Chicken', 'asian', ['korean fried chicken', 'korean chicken', 'kimchi', 'bibimbap', 'korean bun']],
  ['Bao', 'asian', ['bao', 'bao bun', 'dimsum', 'dim sum']],
  ['Falafel', 'middle-eastern', ['falafel', 'falafel wrap', 'hummus', 'pita', 'mezze']],
  ['Mandi', 'middle-eastern', ['mandi', 'chicken mandi', 'kunafa']],
  ['Sizzler', 'continental', ['sizzler', 'veg sizzler', 'chicken sizzler', 'paneer sizzler']],
  ['Grilled Chicken', 'continental', ['grilled chicken', 'chicken steak', 'steak', 'fish and chips', 'mashed potato']],
  ['Waffle', 'waffles', ['waffle', 'waffles', 'belgian waffle', 'chocolate waffle']],
  ['Pancakes', 'waffles', ['pancake', 'pancakes', 'crepe', 'crepes']],
  ['Misal Pav', 'misal', ['misal', 'misal pav', 'missal', 'misal paav', 'misalpav']],
  ['Kolhapuri Misal', 'misal', ['kolhapuri misal', 'kolhapuri missal']],
  ['Vada Pav', 'snacks', ['vada pav', 'wada pav', 'vadapav', 'wadapav', 'batata vada pav']],
  ['Kanda Bhaji', 'snacks', ['kanda bhaji', 'kanda bhaje', 'onion bhaji', 'onion pakoda', 'kanda bhajji']],
  ['Samosa', 'snacks', ['samosa', 'samose']],
  ['Sabudana Vada', 'snacks', ['sabudana vada', 'sabudana wada', 'sago vada']],
  ['Masala Dosa', 'south-indian', ['masala dosa', 'masala dosai', 'masala dose']],
  ['Rava Dosa', 'south-indian', ['rava dosa', 'rawa dosa', 'rava dose']],
  ['Idli Sambar', 'south-indian', ['idli', 'idli sambar', 'idly', 'idly sambhar', 'idli sambhar']],
  ['Medu Vada', 'south-indian', ['medu vada', 'medu wada', 'vada sambar']],
  ['Uttapam', 'south-indian', ['uttapam', 'uthappam', 'uttappa']],
  ['Chicken Dum Biryani', 'biryani', ['chicken biryani', 'chicken dum biryani', 'chicken biriyani', 'murgh biryani']],
  ['Mutton Biryani', 'biryani', ['mutton biryani', 'mutton biriyani', 'gosht biryani']],
  ['Veg Biryani', 'biryani', ['veg biryani', 'vegetable biryani', 'veg biriyani']],
  ['Maharashtrian Thali', 'thali', ['maharashtrian thali', 'marathi thali', 'thali', 'veg thali']],
  ['Cutting Chai', 'chai-coffee', ['cutting chai', 'cutting', 'chai', 'tea', 'masala chai']],
  ['Filter Coffee', 'chai-coffee', ['filter coffee', 'filter kaapi', 'kaapi']],
  ['Cold Coffee', 'chai-coffee', ['cold coffee', 'cold cofee', 'iced coffee', 'thick cold coffee']],
  ['Jalebi', 'desserts', ['jalebi', 'jilebi', 'jalabi']],
  ['Puran Poli', 'desserts', ['puran poli', 'puranpoli', 'puran polli']],
  ['Shrikhand', 'desserts', ['shrikhand', 'shreekhand']],
  ['Rabdi', 'desserts', ['rabdi', 'rabri', 'rabadi']],
  ['Gulab Jamun', 'desserts', ['gulab jamun', 'gulabjamun']],
  ['Pav Bhaji', 'street-food', ['pav bhaji', 'paav bhaji', 'pavbhaji', 'butter pav bhaji']],
  ['Egg Bhurji Pav', 'street-food', ['bhurji pav', 'egg bhurji', 'anda bhurji', 'bhurji']],
  ['Pani Puri', 'street-food', ['pani puri', 'panipuri', 'golgappa', 'gol gappe']],
  ['Bhel', 'street-food', ['bhel', 'bhel puri', 'bhelpuri']],
  ['Dabeli', 'street-food', ['dabeli', 'kutchi dabeli']],
  ['Veg Burger', 'burgers', ['veg burger', 'burger', 'aloo tikki burger']],
  ['Chicken Burger', 'burgers', ['chicken burger', 'crispy chicken burger']],
  ['Bombay Sandwich', 'burgers', ['bombay sandwich', 'veg sandwich', 'grilled sandwich']],
  ['Margherita Pizza', 'pizza-pasta', ['margherita', 'margherita pizza', 'margarita pizza', 'pizza']],
  ['Penne Arrabbiata', 'pizza-pasta', ['penne arrabbiata', 'arrabiata pasta', 'red sauce pasta', 'pasta']],
  ['Schezwan Fried Rice', 'chinese', ['schezwan fried rice', 'szechuan fried rice', 'schezwan rice', 'fried rice']],
  ['Veg Manchurian', 'chinese', ['manchurian', 'veg manchurian', 'gobi manchurian']],
  ['Chilli Chicken', 'chinese', ['chilli chicken', 'chili chicken']],
  ['Hakka Noodles', 'chinese', ['hakka noodles', 'noodles', 'chowmein']],
  ['Sabudana Khichdi', 'breakfast', ['sabudana khichdi', 'sabudana khichadi', 'sago khichdi', 'sabudana']],
  ['Poha', 'breakfast', ['poha', 'kanda poha', 'pohe', 'kande pohe']],
  ['Upma', 'breakfast', ['upma', 'upit', 'uppit']],
  ['Bun Maska', 'breakfast', ['bun maska', 'maska bun', 'bun maska chai']],
  // More of what Nashik eats.
  ['Kachori', 'snacks', ['kachori', 'khasta kachori', 'pyaaz kachori']],
  ['Dhokla', 'snacks', ['dhokla', 'khaman', 'khaman dhokla']],
  ['Batata Vada', 'snacks', ['batata vada', 'batata wada', 'aloo bonda']],
  ['Mysore Masala Dosa', 'south-indian', ['mysore masala dosa', 'mysore dosa', 'mysore masala dose']],
  ['Appe', 'south-indian', ['appe', 'appam', 'paniyaram', 'paddu']],
  ['Sada Dosa', 'south-indian', ['sada dosa', 'plain dosa', 'butter dosa', 'cheese dosa']],
  ['Hyderabadi Biryani', 'biryani', ['hyderabadi biryani', 'hyderabadi dum biryani']],
  ['Egg Biryani', 'biryani', ['egg biryani', 'anda biryani']],
  ['Paneer Biryani', 'biryani', ['paneer biryani']],
  ['Veg Pulao', 'biryani', ['pulao', 'veg pulao', 'jeera pulao', 'tawa pulao']],
  ['Gujarati Thali', 'thali', ['gujarati thali', 'rajasthani thali', 'unlimited thali', 'special thali', 'mini thali']],
  ['Cappuccino', 'chai-coffee', ['cappuccino', 'latte', 'cafe latte', 'americano', 'espresso', 'mocha', 'hot chocolate']],
  ['Green Tea', 'chai-coffee', ['green tea', 'lemon tea', 'ginger tea', 'kadak chai', 'irani chai']],
  ['Modak', 'desserts', ['modak', 'ukadiche modak']],
  ['Rasmalai', 'desserts', ['rasmalai', 'ras malai']],
  ['Kaju Katli', 'desserts', ['kaju katli', 'kaju barfi', 'barfi', 'pedha', 'peda']],
  ['Basundi', 'desserts', ['basundi', 'gajar halwa', 'halwa', 'kheer']],
  ['Sev Puri', 'street-food', ['sev puri', 'sevpuri', 'dahi puri', 'dahi batata puri']],
  ['Ragda Pattice', 'street-food', ['ragda pattice', 'ragda patties', 'ragda']],
  ['Chaat', 'street-food', ['chaat', 'aloo chaat', 'papdi chaat', 'samosa chaat']],
  ['Club Sandwich', 'burgers', ['club sandwich', 'cheese sandwich', 'paneer sandwich', 'chicken sandwich']],
  ['Garlic Bread', 'pizza-pasta', ['garlic bread', 'cheese garlic bread']],
  ['Farmhouse Pizza', 'pizza-pasta', ['farmhouse pizza', 'paneer pizza', 'chicken pizza', 'veg pizza']],
  ['White Sauce Pasta', 'pizza-pasta', ['white sauce pasta', 'alfredo pasta', 'pink sauce pasta', 'mac and cheese']],
  ['Spring Roll', 'chinese', ['spring roll', 'veg spring roll']],
  ['Triple Schezwan Rice', 'chinese', ['triple schezwan rice', 'triple schezwan', 'triple rice']],
  ['Chicken Lollipop', 'chinese', ['chicken lollipop', 'lollipop']],
  ['Paneer Chilli', 'chinese', ['paneer chilli', 'chilli paneer']],
  ['Aloo Paratha', 'breakfast', ['aloo paratha', 'paratha', 'paneer paratha', 'gobi paratha']],
  ['Thalipeeth', 'maharashtrian', ['thalipeeth', 'thalipith']],
  ['Pithla Bhakri', 'maharashtrian', ['pithla bhakri', 'pitla bhakri', 'zunka bhakri', 'jhunka bhakar', 'bhakri']],
  ['Vada Usal', 'maharashtrian', ['vada usal', 'usal pav', 'usal']],
  ['Bharli Vangi', 'maharashtrian', ['bharli vangi', 'bharleli vangi', 'baingan masala']],
  ['Paneer Butter Masala', 'north-indian', ['paneer butter masala', 'paneer makhani', 'butter paneer', 'shahi paneer', 'kadai paneer', 'paneer tikka masala']],
  ['Dal Makhani', 'north-indian', ['dal makhani', 'dal tadka', 'dal fry', 'dal khichdi']],
  ['Butter Chicken', 'north-indian', ['butter chicken', 'murgh makhani', 'chicken tikka masala']],
  ['Chole Bhature', 'north-indian', ['chole bhature', 'chole kulche', 'chole', 'chana masala']],
  ['Butter Naan', 'north-indian', ['butter naan', 'garlic naan', 'naan', 'tandoori roti', 'kulcha']],
  ['Paneer Tikka', 'tandoor', ['paneer tikka', 'malai paneer tikka', 'achari paneer tikka']],
  ['Chicken Tikka', 'tandoor', ['chicken tikka', 'malai tikka', 'reshmi kebab', 'chicken kebab']],
  ['Tandoori Chicken', 'tandoor', ['tandoori chicken', 'tangdi kebab', 'chicken tandoori']],
  ['Seekh Kebab', 'tandoor', ['seekh kebab', 'sheekh kabab', 'mutton seekh']],
  ['Kolhapuri Chicken', 'non-veg-curry', ['kolhapuri chicken', 'chicken kolhapuri', 'chicken handi', 'chicken curry', 'chicken masala']],
  ['Mutton Rassa', 'non-veg-curry', ['mutton rassa', 'tambda rassa', 'pandhra rassa', 'mutton thali', 'mutton curry', 'mutton masala']],
  ['Egg Curry', 'non-veg-curry', ['egg curry', 'anda curry', 'egg masala']],
  ['Fish Thali', 'seafood', ['fish thali', 'surmai thali', 'pomfret thali']],
  ['Surmai Fry', 'seafood', ['surmai fry', 'fish fry', 'pomfret fry', 'bombil fry', 'rava fry']],
  ['Prawns Masala', 'seafood', ['prawns masala', 'prawns fry', 'kolambi', 'prawn curry', 'fish curry']],
  ['Paneer Roll', 'rolls', ['paneer roll', 'kathi roll', 'frankie', 'veg frankie', 'wrap']],
  ['Chicken Roll', 'rolls', ['chicken roll', 'chicken frankie', 'egg roll', 'shawarma', 'chicken shawarma']],
  ['Veg Momos', 'momos', ['momos', 'momo', 'veg momos', 'steamed momos', 'fried momos', 'paneer momos']],
  ['Chicken Momos', 'momos', ['chicken momos', 'chicken momo', 'tandoori momos']],
  ['Kulfi', 'ice-cream', ['kulfi', 'matka kulfi', 'falooda', 'kulfi falooda']],
  ['Ice Cream', 'ice-cream', ['ice cream', 'icecream', 'sundae', 'gelato', 'scoop']],
  ['Milkshake', 'ice-cream', ['milkshake', 'shake', 'thick shake', 'oreo shake', 'mastani', 'mango mastani']],
  ['Cake', 'bakery', ['cake', 'pastry', 'cheesecake', 'chocolate cake', 'red velvet']],
  ['Brownie', 'bakery', ['brownie', 'cookies', 'cookie', 'muffin', 'donut', 'croissant']],
  ['Veg Puff', 'bakery', ['veg puff', 'puff', 'patties', 'khari', 'toast']],
  ['Sugarcane Juice', 'drinks', ['sugarcane juice', 'ganne ka ras', 'usacha ras', 'rasvanti']],
  ['Lassi', 'drinks', ['lassi', 'sweet lassi', 'mango lassi', 'chaas', 'buttermilk', 'taak']],
  ['Fresh Juice', 'drinks', ['fresh juice', 'orange juice', 'mosambi juice', 'watermelon juice', 'kokum sharbat', 'sharbat', 'fresh lime soda', 'nimbu pani', 'mojito', 'mocktail']],
  ['Caesar Salad', 'healthy', ['salad', 'caesar salad', 'greek salad', 'quinoa bowl', 'buddha bowl', 'sprouts', 'fruit bowl']],
];

export interface SeedDish {
  key: string;
  name: string;
  category: string;
  price: number;
  veg: boolean;
  caption: string;
  featured?: number;
}

export interface SeedRestaurant {
  key: string;
  name: string;
  locality: string;
  lat: number;
  lng: number;
  priceBand: number;
  dishes: SeedDish[];
}

export const restaurants: SeedRestaurant[] = [
  {
    key: 'cafe_ananda', name: 'Cafe Ananda', locality: 'College Road', lat: 20.0063, lng: 73.7638, priceBand: 2,
    dishes: [
      { key: 'ananda_sabudana_khichdi', name: 'Sabudana Khichdi', category: 'breakfast', price: 80, veg: true, caption: 'Soft pearls, lots of peanuts, a squeeze of lime.', featured: 1 },
      { key: 'ananda_cold_coffee', name: 'Cold Coffee', category: 'chai-coffee', price: 120, veg: true, caption: 'Thick enough to need a spoon.', featured: 4 },
      { key: 'ananda_masala_dosa', name: 'Masala Dosa', category: 'south-indian', price: 110, veg: true, caption: 'Crisp edges, buttery potato filling.' },
    ],
  },
  {
    key: 'godavari_misal', name: 'Godavari Misal House', locality: 'Panchavati', lat: 20.009, lng: 73.792, priceBand: 1,
    dishes: [
      { key: 'godavari_kolhapuri_misal', name: 'Kolhapuri Misal', category: 'misal', price: 90, veg: true, caption: 'Fiery tarri — ask for extra farsan.', featured: 2 },
      { key: 'godavari_misal_pav', name: 'Misal Pav', category: 'misal', price: 70, veg: true, caption: 'The classic Nashik breakfast.' },
      { key: 'godavari_kanda_bhaji', name: 'Kanda Bhaji', category: 'snacks', price: 50, veg: true, caption: 'Perfect with rain and chai.' },
    ],
  },
  {
    key: 'bhurji_corner', name: 'Bhurji Corner', locality: 'Nashik Road', lat: 19.952, lng: 73.838, priceBand: 1,
    dishes: [
      { key: 'bhurji_egg_bhurji_pav', name: 'Egg Bhurji Pav', category: 'street-food', price: 60, veg: false, caption: 'Tiny stall, huge flavour. Open late.', featured: 3 },
      { key: 'bhurji_anda_ghotala', name: 'Anda Ghotala', category: 'street-food', price: 80, veg: false, caption: 'Boiled egg, omelette and bhurji in one pan.' },
    ],
  },
  {
    key: 'tandoor_tales', name: 'Tandoor Tales', locality: 'Gangapur Road', lat: 20.0156, lng: 73.7586, priceBand: 3,
    dishes: [
      { key: 'tandoor_chicken_biryani', name: 'Chicken Dum Biryani', category: 'biryani', price: 280, veg: false, caption: 'Sealed handi, long-grain rice, tender pieces.', featured: 5 },
      { key: 'tandoor_paneer_tikka', name: 'Paneer Tikka', category: 'street-food', price: 240, veg: true, caption: 'Smoky and charred at the edges.' },
    ],
  },
  {
    key: 'annapurna_thali', name: 'Annapurna Thali', locality: 'Sharanpur Road', lat: 20.001, lng: 73.78, priceBand: 2,
    dishes: [
      { key: 'annapurna_thali', name: 'Maharashtrian Thali', category: 'thali', price: 220, veg: true, caption: 'Unlimited refills of bhakri and amti.', featured: 6 },
      { key: 'annapurna_puran_poli', name: 'Puran Poli', category: 'desserts', price: 60, veg: true, caption: 'With a generous spoon of ghee.' },
    ],
  },
  {
    key: 'chai_katta', name: 'Chai Katta', locality: 'Canada Corner', lat: 20.005, lng: 73.772, priceBand: 1,
    dishes: [
      { key: 'katta_cutting_chai', name: 'Cutting Chai', category: 'chai-coffee', price: 15, veg: true, caption: 'Strong, sweet, ginger-heavy.' },
      { key: 'katta_bun_maska', name: 'Bun Maska', category: 'breakfast', price: 30, veg: true, caption: 'Dip it in the chai.' },
      { key: 'katta_vada_pav', name: 'Vada Pav', category: 'snacks', price: 20, veg: true, caption: 'Garlic chutney is the secret.', featured: 7 },
    ],
  },
  {
    key: 'dragon_wok', name: 'Dragon Wok', locality: 'CIDCO', lat: 19.987, lng: 73.783, priceBand: 2,
    dishes: [
      { key: 'wok_schezwan_rice', name: 'Schezwan Fried Rice', category: 'chinese', price: 160, veg: true, caption: 'Indo-Chinese done right.' },
      { key: 'wok_chilli_chicken', name: 'Chilli Chicken', category: 'chinese', price: 220, veg: false, caption: 'Dry version, extra green chillies.' },
      { key: 'wok_manchurian', name: 'Veg Manchurian', category: 'chinese', price: 150, veg: true, caption: 'Gravy that goes with everything.' },
    ],
  },
  {
    key: 'slice_of_nashik', name: 'Slice of Nashik', locality: 'College Road', lat: 20.0071, lng: 73.7655, priceBand: 3,
    dishes: [
      { key: 'slice_margherita', name: 'Margherita Pizza', category: 'pizza-pasta', price: 250, veg: true, caption: 'Wood-fired, thin crust.' },
      { key: 'slice_penne', name: 'Penne Arrabbiata', category: 'pizza-pasta', price: 230, veg: true, caption: 'Garlicky and properly spicy.' },
    ],
  },
  {
    key: 'sweet_sangam', name: 'Sweet Sangam', locality: 'Panchavati', lat: 20.0102, lng: 73.7935, priceBand: 1,
    dishes: [
      { key: 'sangam_jalebi', name: 'Jalebi', category: 'desserts', price: 40, veg: true, caption: 'Hot from the kadhai every evening.', featured: 8 },
      { key: 'sangam_rabdi', name: 'Rabdi', category: 'desserts', price: 70, veg: true, caption: 'Pair it with the jalebi.' },
      { key: 'sangam_shrikhand', name: 'Shrikhand', category: 'desserts', price: 60, veg: true, caption: 'Kesar-elaichi, not too sweet.' },
    ],
  },
  {
    key: 'burger_barn', name: 'Burger Barn', locality: 'Gangapur Road', lat: 20.0142, lng: 73.7601, priceBand: 2,
    dishes: [
      { key: 'barn_veg_burger', name: 'Veg Burger', category: 'burgers', price: 120, veg: true, caption: 'Crunchy aloo tikki, toasted bun.' },
      { key: 'barn_chicken_burger', name: 'Chicken Burger', category: 'burgers', price: 170, veg: false, caption: 'Crispy fried fillet with spicy mayo.' },
    ],
  },
  {
    key: 'udupi_swad', name: 'Udupi Swad', locality: 'Indira Nagar', lat: 19.976, lng: 73.798, priceBand: 1,
    dishes: [
      { key: 'udupi_rava_dosa', name: 'Rava Dosa', category: 'south-indian', price: 100, veg: true, caption: 'Lacy and crisp with onions.' },
      { key: 'udupi_idli', name: 'Idli Sambar', category: 'south-indian', price: 60, veg: true, caption: 'Cloud-soft idlis.' },
      { key: 'udupi_filter_coffee', name: 'Filter Coffee', category: 'chai-coffee', price: 30, veg: true, caption: 'Served in a steel davara.' },
    ],
  },
  {
    key: 'pav_bhaji_point', name: 'Pav Bhaji Point', locality: 'Canada Corner', lat: 20.0042, lng: 73.7731, priceBand: 1,
    dishes: [
      { key: 'pbp_pav_bhaji', name: 'Pav Bhaji', category: 'street-food', price: 110, veg: true, caption: 'Butter, butter, butter.' },
      { key: 'pbp_tawa_pulao', name: 'Tawa Pulao', category: 'street-food', price: 120, veg: true, caption: 'Made on the same tawa as the bhaji.' },
    ],
  },
];

// Relative popularity used only to generate demo activity (DEMO_ACTIVITY=true).
// Bhurji Corner is deliberately a quiet place with a well-loved dish: a hidden gem.
export const demoPopularity: Record<string, number> = {
  ananda_sabudana_khichdi: 0.55, ananda_cold_coffee: 0.6, ananda_masala_dosa: 0.3,
  godavari_kolhapuri_misal: 0.7, godavari_misal_pav: 0.45, godavari_kanda_bhaji: 0.2,
  bhurji_egg_bhurji_pav: 0.41, bhurji_anda_ghotala: 0.03,
  tandoor_chicken_biryani: 0.5, tandoor_paneer_tikka: 0.15,
  annapurna_thali: 0.38, annapurna_puran_poli: 0.25,
  katta_cutting_chai: 0.35, katta_bun_maska: 0.12, katta_vada_pav: 0.62,
  wok_schezwan_rice: 0.18, wok_chilli_chicken: 0.22, wok_manchurian: 0.1,
  slice_margherita: 0.28, slice_penne: 0.06,
  sangam_jalebi: 0.42, sangam_rabdi: 0.14, sangam_shrikhand: 0.05,
  barn_veg_burger: 0.1, barn_chicken_burger: 0.16,
  udupi_rava_dosa: 0.2, udupi_idli: 0.26, udupi_filter_coffee: 0.3,
  pbp_pav_bhaji: 0.48, pbp_tawa_pulao: 0.04,
};
